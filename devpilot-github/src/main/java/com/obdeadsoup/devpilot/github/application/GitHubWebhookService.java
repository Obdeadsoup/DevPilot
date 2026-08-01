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
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.Set;

/**
 * GitHub Webhook 接收 Application Service。
 * 在同一事务中基于原始字节验签并持久化 Inbox Delivery，提交后再触发异步处理。
 */
@Service
public class GitHubWebhookService {

    private static final Set<String> SUPPORTED_EVENTS = Set.of("ping", "push");
    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubWebhookService.class);

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

    /**
     * 校验 Header、Binding、HMAC 和 Payload 大小后持久化 Delivery。
     * 相同 Delivery ID 只有 Repository、Event 与 Payload SHA-256 全部一致才作为正常重复返回。
     */
    @Transactional
    public GitHubWebhookReceiptResponse receive(
            byte[] rawBody,
            String signatureHeader,
            String deliveryIdHeader,
            String eventHeader
    ) {
        LOGGER.debug(
                "GitHub webhook received event={} deliveryId={} rawBodyLength={} signaturePresent={}",
                eventHeader, deliveryIdHeader, rawBody == null ? 0 : rawBody.length,
                signatureHeader != null && !signatureHeader.isBlank()
        );
        try {
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
            var repositoryResult = repositoryMapper.findByGitHubRepositoryId(githubRepositoryId);
            LOGGER.debug(
                    "GitHub webhook repository lookup repositoryId={} repositoryFound={}",
                    githubRepositoryId, repositoryResult.isPresent()
            );
            GitHubRepositoryEntity repository = repositoryResult
                    .orElseThrow(() -> new BusinessException(GitHubWebhookErrorCode.REPOSITORY_NOT_FOUND));
            boolean activeBinding = GitHubRepositoryStatus.ACTIVE.name().equals(repository.bindingStatus());
            LOGGER.debug("GitHub webhook binding repositoryId={} active={}", githubRepositoryId, activeBinding);
            if (!activeBinding) {
                throw new BusinessException(GitHubWebhookErrorCode.REPOSITORY_DISABLED);
            }
            var secretResult = secretResolver.resolve(repository.webhookSecretRef());
            LOGGER.debug(
                    "GitHub webhook secret resolution webhookSecretRef={} secretResolved={}",
                    repository.webhookSecretRef(), secretResult.isPresent()
            );
            String secret = secretResult
                    .orElseThrow(() -> new BusinessException(GitHubWebhookErrorCode.SECRET_UNAVAILABLE));
            boolean signatureValid = signatureVerifier.verify(rawBody, signatureHeader, secret);
            LOGGER.debug("GitHub webhook signature validation repositoryId={} valid={}", githubRepositoryId, signatureValid);
            if (!signatureValid) {
                throw new BusinessException(GitHubWebhookErrorCode.SIGNATURE_INVALID);
            }

            LocalDateTime receivedAt = LocalDateTime.now(clock);
            String payloadJson = new String(rawBody, StandardCharsets.UTF_8);
            String payloadSha256 = signatureVerifier.sha256Hex(rawBody);
            boolean inserted;
            try {
                deliveryMapper.insertReceived(
                        repository.workspaceId(), repository.projectId(), repository.id(), deliveryId, eventType,
                        payloadParser.extractAction(rawBody), payloadJson, payloadSha256, receivedAt
                );
                inserted = true;
            } catch (DuplicateKeyException exception) {
                inserted = false;
            }
            GitHubDeliveryEntity delivery = deliveryMapper.findByGitHubDeliveryId(deliveryId)
                    .orElseThrow(() -> new BusinessException(GitHubWebhookErrorCode.DELIVERY_STATE_CONFLICT));
            requireMatchingDuplicate(delivery, repository, eventType, payloadSha256);
            if (inserted) {
                eventPublisher.publishEvent(new GitHubDeliveryReceivedEvent(delivery.id()));
            }
            return new GitHubWebhookReceiptResponse(
                    delivery.githubDeliveryId(), delivery.processingStatus(), !inserted
            );
        } catch (BusinessException exception) {
            LOGGER.debug("GitHub webhook rejected errorCode={}", exception.errorCode().code());
            throw exception;
        }
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
            String eventType,
            String payloadSha256
    ) {
        if (delivery.repositoryId() != repository.id()
                || !delivery.eventType().equals(eventType)
                || !delivery.payloadSha256().equals(payloadSha256)) {
            throw new BusinessException(GitHubWebhookErrorCode.DELIVERY_STATE_CONFLICT);
        }
    }
}
