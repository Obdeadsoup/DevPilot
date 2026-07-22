package com.obdeadsoup.devpilot.github.persistence.entity;

import java.time.LocalDateTime;

public record GitHubDeliveryEntity(
        long id,
        long workspaceId,
        long projectId,
        long repositoryId,
        String githubDeliveryId,
        String eventType,
        String action,
        String processingStatus,
        String payloadJson,
        String payloadSha256,
        int retryCount,
        LocalDateTime receivedAt,
        long version
) {
}
