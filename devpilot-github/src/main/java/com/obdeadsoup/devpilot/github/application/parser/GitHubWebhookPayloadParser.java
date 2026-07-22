package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubWebhookErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;
import com.obdeadsoup.devpilot.project.domain.ProjectActivitySourceType;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

@Component
public class GitHubWebhookPayloadParser {

    private final ObjectMapper objectMapper;

    public GitHubWebhookPayloadParser(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public long extractRepositoryId(byte[] rawBody) {
        try {
            JsonNode repositoryId = objectMapper.readTree(rawBody).path("repository").path("id");
            if (!repositoryId.canConvertToLong() || repositoryId.longValue() <= 0) {
                throw malformedPayload();
            }
            return repositoryId.longValue();
        } catch (IOException exception) {
            throw malformedPayload();
        }
    }

    public String extractAction(byte[] rawBody) {
        try {
            JsonNode action = objectMapper.readTree(rawBody).path("action");
            return action.isTextual() ? truncate(action.textValue(), 50) : null;
        } catch (IOException exception) {
            throw malformedPayload();
        }
    }

    public RecordProjectActivityCommand parse(GitHubDeliveryEntity delivery) {
        return switch (delivery.eventType()) {
            case "ping" -> parsePing(delivery);
            case "push" -> parsePush(delivery);
            default -> throw new BusinessException(GitHubWebhookErrorCode.UNSUPPORTED_EVENT);
        };
    }

    private RecordProjectActivityCommand parsePing(GitHubDeliveryEntity delivery) {
        PingWebhookPayload payload = read(delivery.payloadJson(), PingWebhookPayload.class);
        requireRepository(payload.repository());
        String fullName = truncate(payload.repository().fullName(), 201);
        GitHubSenderPayload sender = payload.sender();
        return new RecordProjectActivityCommand(
                delivery.workspaceId(), delivery.projectId(), payload.repository().id(), fullName,
                ProjectActivitySourceType.GITHUB, ProjectActivityType.GITHUB_WEBHOOK_PING,
                delivery.githubDeliveryId(), sender == null ? null : sender.id(),
                sender == null ? null : truncate(sender.login(), 100), null, null, null, null, null,
                "GitHub webhook ping",
                fullName == null ? "GitHub webhook configuration verified"
                        : "GitHub webhook configuration verified for " + fullName,
                null, delivery.receivedAt()
        );
    }

    private RecordProjectActivityCommand parsePush(GitHubDeliveryEntity delivery) {
        PushWebhookPayload payload = read(delivery.payloadJson(), PushWebhookPayload.class);
        requireRepository(payload.repository());
        GitHubSenderPayload sender = payload.sender();
        List<PushWebhookPayload.CommitPayload> commits = payload.commits() == null ? List.of() : payload.commits();
        PushWebhookPayload.HeadCommitPayload headCommit = payload.head_commit();
        String fullName = truncate(payload.repository().fullName(), 201);
        String ref = truncate(payload.ref(), 500);
        String title = "Code pushed" + (fullName == null ? "" : " to " + fullName);
        String summary = commits.size() + " commit(s) pushed" + (ref == null ? "" : " to " + ref);
        return new RecordProjectActivityCommand(
                delivery.workspaceId(), delivery.projectId(), payload.repository().id(), fullName,
                ProjectActivitySourceType.GITHUB, ProjectActivityType.CODE_PUSHED,
                delivery.githubDeliveryId(), sender == null ? null : sender.id(),
                sender == null ? null : truncate(sender.login(), 100), ref,
                truncate(payload.before(), 40), truncate(payload.after(), 40), commits.size(),
                headCommit == null ? null : truncate(headCommit.message(), 1000),
                truncate(title, 255), truncate(summary, 2000), truncate(payload.compare(), 500),
                occurredAt(headCommit, delivery.receivedAt())
        );
    }

    private LocalDateTime occurredAt(PushWebhookPayload.HeadCommitPayload headCommit, LocalDateTime fallback) {
        if (headCommit == null || headCommit.timestamp() == null) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(headCommit.timestamp()).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private <T> T read(String payloadJson, Class<T> type) {
        try {
            return objectMapper.readValue(payloadJson, type);
        } catch (IOException exception) {
            throw malformedPayload();
        }
    }

    private void requireRepository(GitHubRepositoryPayload repository) {
        if (repository == null || repository.id() == null || repository.id() <= 0) {
            throw malformedPayload();
        }
    }

    private BusinessException malformedPayload() {
        return new BusinessException(GitHubWebhookErrorCode.MALFORMED_PAYLOAD);
    }

    private String truncate(String value, int maximumLength) {
        if (value == null || value.length() <= maximumLength) {
            return value;
        }
        return value.substring(0, maximumLength);
    }
}
