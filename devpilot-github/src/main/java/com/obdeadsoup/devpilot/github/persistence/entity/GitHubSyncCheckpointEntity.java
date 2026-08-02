package com.obdeadsoup.devpilot.github.persistence.entity;

import java.time.LocalDateTime;

public record GitHubSyncCheckpointEntity(
        long id,
        long repositoryBindingId,
        String resourceType,
        LocalDateTime lastSuccessfulSyncAt,
        String lastSeenCommitSha,
        long overlapSeconds,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version
) {
}
