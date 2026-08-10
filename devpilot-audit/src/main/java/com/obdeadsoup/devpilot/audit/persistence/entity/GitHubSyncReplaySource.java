package com.obdeadsoup.devpilot.audit.persistence.entity;

public record GitHubSyncReplaySource(
        long id, long bindingId, String resourceType, String status, int attemptCount, long version) {
}
