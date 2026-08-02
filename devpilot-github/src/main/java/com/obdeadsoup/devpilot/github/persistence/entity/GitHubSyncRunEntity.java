package com.obdeadsoup.devpilot.github.persistence.entity;

import java.time.LocalDateTime;

public record GitHubSyncRunEntity(
        long id,
        long repositoryBindingId,
        String resourceType,
        String triggerType,
        String status,
        int attemptCount,
        LocalDateTime nextRetryAt,
        LocalDateTime startedAt,
        LocalDateTime completedAt,
        String lastErrorCode,
        String lastErrorMessage,
        Long requestedBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version
) {
}
