package com.obdeadsoup.devpilot.github.application.parser;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@JsonIgnoreProperties(ignoreUnknown = true)
public record PingWebhookPayload(
        GitHubRepositoryPayload repository,
        GitHubSenderPayload sender
) {
}
