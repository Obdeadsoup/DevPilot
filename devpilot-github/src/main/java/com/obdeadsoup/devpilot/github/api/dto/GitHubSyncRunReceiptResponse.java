package com.obdeadsoup.devpilot.github.api.dto;

public record GitHubSyncRunReceiptResponse(long runId, String status, boolean existing) {
}
