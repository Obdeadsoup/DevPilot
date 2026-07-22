package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubWebhookPayloadParserTest {

    private final GitHubWebhookPayloadParser parser = new GitHubWebhookPayloadParser(new ObjectMapper());

    @Test
    void parsesPingWithoutMixingPayloadAndPersistenceTypes() {
        String json = """
                {"repository":{"id":123,"full_name":"octo/demo"},"sender":{"id":7,"login":"octocat"}}
                """;

        RecordProjectActivityCommand activity = parser.parse(delivery("ping", json));

        assertThat(activity.activityType()).isEqualTo(ProjectActivityType.GITHUB_WEBHOOK_PING);
        assertThat(activity.githubRepositoryId()).isEqualTo(123L);
        assertThat(activity.actorLogin()).isEqualTo("octocat");
    }

    @Test
    void parsesRequiredPushFields() {
        String json = """
                {
                  "ref":"refs/heads/main",
                  "before":"1111111111111111111111111111111111111111",
                  "after":"2222222222222222222222222222222222222222",
                  "compare":"https://github.com/octo/demo/compare/1...2",
                  "repository":{"id":123,"full_name":"octo/demo"},
                  "sender":{"id":7,"login":"octocat"},
                  "commits":[{"id":"a"},{"id":"b"}],
                  "head_commit":{"id":"b","message":"ship it","timestamp":"2026-07-21T10:15:30+00:00"}
                }
                """;

        RecordProjectActivityCommand activity = parser.parse(delivery("push", json));

        assertThat(activity.activityType()).isEqualTo(ProjectActivityType.CODE_PUSHED);
        assertThat(activity.gitRef()).isEqualTo("refs/heads/main");
        assertThat(activity.beforeSha()).startsWith("1111");
        assertThat(activity.afterSha()).startsWith("2222");
        assertThat(activity.commitCount()).isEqualTo(2);
        assertThat(activity.headCommitMessage()).isEqualTo("ship it");
        assertThat(activity.externalUrl()).endsWith("1...2");
        assertThat(activity.occurredAt()).isEqualTo(LocalDateTime.of(2026, 7, 21, 10, 15, 30));
    }

    private GitHubDeliveryEntity delivery(String eventType, String payloadJson) {
        return new GitHubDeliveryEntity(
                1, 10, 20, 30, "delivery-1", eventType, null, "PROCESSING",
                payloadJson, "hash", 0, LocalDateTime.of(2026, 7, 21, 9, 0), 1
        );
    }
}
