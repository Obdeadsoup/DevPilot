package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.api.dto.GitHubWebhookReceiptResponse;
import com.obdeadsoup.devpilot.github.application.event.GitHubDeliveryReceivedEvent;
import com.obdeadsoup.devpilot.github.application.parser.GitHubWebhookPayloadParser;
import com.obdeadsoup.devpilot.github.application.secret.WebhookSecretResolver;
import com.obdeadsoup.devpilot.github.application.security.GitHubWebhookSignatureVerifier;
import com.obdeadsoup.devpilot.github.config.GitHubIntegrationProperties;
import com.obdeadsoup.devpilot.github.domain.GitHubRepositoryStatus;
import com.obdeadsoup.devpilot.github.error.GitHubWebhookErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

@Service
public class GitHubWebhookService {

    private static final Set<String> SUPPORTED_EVENTS = Set.of("ping", "push");

    private final GitHubIntegrationProperties properties;
    private final GitHubRepositoryMapper repositoryMapper;
    private final GitHubDeliveryMapper deliveryMapper;
    private final GitHubWebhookPayloadParser payloadParser;
    private final GitHubWebhookSignatureVerifier signatureVerifier;
    private final WebhookSecretResolver secretResolver;
    private final ApplicationEventPublisher eventPublisher;
    private final Clock clock;

    public GitHubWebhookService(
            GitHubIntegrationProperties properties,
            GitHubRepositoryMapper repositoryMapper,
            GitHubDeliveryMapper deliveryMapper,
            GitHubWebhookPayloadParser payloadParser,
            GitHubWebhookSignatureVerifier signatureVerifier,
            WebhookSecretResolver secretResolver,
            ApplicationEventPublisher eventPublisher,
            Clock clock
    ) {
        this.properties = properties;
        this.repositoryMapper = repositoryMapper;
        this.deliveryMapper = deliveryMapper;
        this.payloadParser = payloadParser;
        this.signatureVerifier = signatureVerifier;
        this.secretResolver = secretResolver;
        this.eventPublisher = eventPublisher;
        this.clock = clock;
    }

    @Transactional
    public GitHubWebhookReceiptResponse receive(
            byte[] rawBody,
            String signatureHeader,
            String deliveryIdHeader,
            String eventHeader
    ) {
        requireHeader(signatureHeader, "X-Hub-Signature-256");
        requireHeader(deliveryIdHeader, "X-GitHub-Delivery");
        requireHeader(eventHeader, "X-GitHub-Event");
        if (rawBody == null || rawBody.length == 0) {
            throw new BusinessException(GitHubWebhookErrorCode.MALFORMED_PAYLOAD);
        }
        if (rawBody.length > properties.webhookMaxPayloadBytes()) {
            throw new BusinessException(GitHubWebhookErrorCode.PAYLOAD_TOO_LARGE);
        }
        String deliveryId = deliveryIdHeader.trim();
        if (deliveryId.length() > 100) {
            throw new BusinessException(GitHubWebhookErrorCode.INVALID_DELIVERY_ID);
        }
        String eventType = eventHeader.trim().toLowerCase(Locale.ROOT);
        if (!SUPPORTED_EVENTS.contains(eventType)) {
            throw new BusinessException(GitHubWebhookErrorCode.UNSUPPORTED_EVENT);
        }

        long githubRepositoryId = payloadParser.extractRepositoryId(rawBody);
        GitHubRepositoryEntity repository = repositoryMapper.findByGitHubRepositoryId(githubRepositoryId)
                .orElseThrow(() -> new BusinessException(GitHubWebhookErrorCode.REPOSITORY_NOT_FOUND));
        if (!GitHubRepositoryStatus.ACTIVE.name().equals(repository.bindingStatus())) {
            throw new BusinessException(GitHubWebhookErrorCode.REPOSITORY_DISABLED);
        }
        String secret = secretResolver.resolve(repository.credentialRef())
                .orElseThrow(() -> new BusinessException(GitHubWebhookErrorCode.SECRET_UNAVAILABLE));
        if (!signatureVerifier.verify(rawBody, signatureHeader, secret)) {
            throw new BusinessException(GitHubWebhookErrorCode.SIGNATURE_INVALID);
        }

        LocalDateTime receivedAt = LocalDateTime.now(clock);
        String payloadJson = new String(rawBody, StandardCharsets.UTF_8);
        boolean inserted;
        try {
            deliveryMapper.insertReceived(
                    repository.workspaceId(), repository.projectId(), repository.id(), deliveryId, eventType,
                    payloadParser.extractAction(rawBody), payloadJson, signatureVerifier.sha256Hex(rawBody), receivedAt
            );
            inserted = true;
        } catch (DuplicateKeyException exception) {
            inserted = false;
        }
        GitHubDeliveryEntity delivery = deliveryMapper.findByGitHubDeliveryId(deliveryId)
                .orElseThrow(() -> new BusinessException(GitHubWebhookErrorCode.DELIVERY_STATE_CONFLICT));
        requireMatchingDuplicate(delivery, repository, eventType);
        if (inserted) {
            eventPublisher.publishEvent(new GitHubDeliveryReceivedEvent(delivery.id()));
        }
        return new GitHubWebhookReceiptResponse(
                delivery.githubDeliveryId(), delivery.processingStatus(), !inserted
        );
    }

    private void requireHeader(String value, String headerName) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(
                    GitHubWebhookErrorCode.MISSING_HEADER,
                    "Required header is missing: " + headerName
            );
        }
    }

    private void requireMatchingDuplicate(
            GitHubDeliveryEntity delivery,
            GitHubRepositoryEntity repository,
            String eventType
    ) {
        if (delivery.repositoryId() != repository.id() || !delivery.eventType().equals(eventType)) {
            throw new BusinessException(GitHubWebhookErrorCode.DELIVERY_STATE_CONFLICT);
        }
    }
}
