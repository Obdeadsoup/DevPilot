package com.obdeadsoup.devpilot.github.persistence.entity;

import java.time.LocalDateTime;

public record GitHubRepositoryEntity(
        long id,
        long workspaceId,
        long projectId,
        long githubRepositoryId,
        String ownerLogin,
        String repositoryName,
        String fullName,
        String htmlUrl,
        String defaultBranch,
        String visibility,
        String bindingStatus,
        String webhookSecretRef,
        String apiCredentialRef,
        LocalDateTime lastSyncedAt,
        LocalDateTime lastVerifiedAt,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version
) {
}
