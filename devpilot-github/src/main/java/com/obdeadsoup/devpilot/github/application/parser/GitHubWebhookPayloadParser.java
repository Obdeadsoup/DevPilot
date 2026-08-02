package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubWebhookErrorCode;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubCommitCommand;
import com.obdeadsoup.devpilot.github.domain.GitHubCommitSource;
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
import java.util.ArrayList;

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

    /** 解析 Delivery 的聚合 Activity 与 Push Commit 明细，供成功处理事务一次消费。 */
    public GitHubWebhookProcessingPlan parseForProcessing(GitHubDeliveryEntity delivery) {
        return switch (delivery.eventType()) {
            case "ping" -> new GitHubWebhookProcessingPlan(parsePing(delivery), List.of());
            case "push" -> {
                PushWebhookPayload payload = read(delivery.payloadJson(), PushWebhookPayload.class);
                requireRepository(payload.repository());
                yield new GitHubWebhookProcessingPlan(
                        pushActivity(delivery, payload),
                        pushCommits(delivery, payload)
                );
            }
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
        return pushActivity(delivery, payload);
    }

    private RecordProjectActivityCommand pushActivity(
            GitHubDeliveryEntity delivery,
            PushWebhookPayload payload
    ) {
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

    private List<UpsertGitHubCommitCommand> pushCommits(
            GitHubDeliveryEntity delivery,
            PushWebhookPayload payload
    ) {
        List<PushWebhookPayload.CommitPayload> payloadCommits =
                payload.commits() == null ? List.of() : payload.commits();
        List<UpsertGitHubCommitCommand> commands = new ArrayList<>(payloadCommits.size());
        for (PushWebhookPayload.CommitPayload commit : payloadCommits) {
            if (commit == null || commit.id() == null || !commit.id().matches("[0-9a-fA-F]{40}")) {
                throw malformedPayload();
            }
            PushWebhookPayload.HeadCommitPayload head = payload.head_commit();
            boolean isHead = head != null && commit.id().equalsIgnoreCase(head.id());
            String message = firstNonBlank(commit.message(), isHead ? head.message() : null);
            String timestamp = firstNonBlank(commit.timestamp(), isHead ? head.timestamp() : null);
            String url = firstNonBlank(commit.url(), isHead ? head.url() : null);
            PushWebhookPayload.CommitAuthorPayload author = commit.author() != null
                    ? commit.author()
                    : isHead ? head.author() : null;
            commands.add(new UpsertGitHubCommitCommand(
                    delivery.workspaceId(),
                    delivery.projectId(),
                    delivery.repositoryId(),
                    payload.repository().id(),
                    payload.repository().fullName(),
                    commit.id(),
                    message,
                    author == null ? null : author.name(),
                    author == null ? null : author.email(),
                    null,
                    author == null ? null : author.username(),
                    occurredAt(timestamp, delivery.receivedAt()),
                    url == null ? commitUrl(payload.repository().fullName(), commit.id()) : url,
                    GitHubCommitSource.WEBHOOK
            ));
        }
        return commands;
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

    private LocalDateTime occurredAt(String timestamp, LocalDateTime fallback) {
        if (timestamp == null) {
            return fallback;
        }
        try {
            return OffsetDateTime.parse(timestamp).withOffsetSameInstant(ZoneOffset.UTC).toLocalDateTime();
        } catch (RuntimeException exception) {
            return fallback;
        }
    }

    private String commitUrl(String fullName, String sha) {
        return fullName == null ? null : "https://github.com/" + fullName + "/commit/" + sha;
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
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
