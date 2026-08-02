package com.obdeadsoup.devpilot.github.api.dto;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;

import java.time.LocalDateTime;

public record GitHubSyncRunResponse(
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
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static GitHubSyncRunResponse from(GitHubSyncRunEntity entity) {
        return new GitHubSyncRunResponse(
                entity.id(), entity.repositoryBindingId(), entity.resourceType(), entity.triggerType(),
                entity.status(), entity.attemptCount(), entity.nextRetryAt(), entity.startedAt(),
                entity.completedAt(), entity.lastErrorCode(), entity.createdAt(), entity.updatedAt()
        );
    }
}
