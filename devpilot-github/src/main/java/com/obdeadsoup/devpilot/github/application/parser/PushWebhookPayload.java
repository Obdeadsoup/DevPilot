package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PushWebhookPayload(
        GitHubRepositoryPayload repository,
        GitHubSenderPayload sender,
        String ref,
        String before,
        String after,
        String compare,
        List<CommitPayload> commits,
        HeadCommitPayload head_commit
) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record CommitPayload(String id) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    public record HeadCommitPayload(String id, String message, String timestamp) {
    }
}
