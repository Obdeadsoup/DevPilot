package com.obdeadsoup.devpilot.github.api.dto;

import com.obdeadsoup.devpilot.github.domain.GitHubRepositoryStatus;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;

import java.time.LocalDateTime;

public record GitHubRepositoryResponse(
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
        GitHubRepositoryStatus bindingStatus,
        boolean hasApiCredential,
        boolean hasWebhookSecret,
        LocalDateTime lastSyncedAt,
        LocalDateTime lastVerifiedAt,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version
) {

    public static GitHubRepositoryResponse from(GitHubRepositoryEntity entity) {
        return new GitHubRepositoryResponse(
                entity.id(),
                entity.workspaceId(),
                entity.projectId(),
                entity.githubRepositoryId(),
                entity.ownerLogin(),
                entity.repositoryName(),
                entity.fullName(),
                entity.htmlUrl(),
                entity.defaultBranch(),
                entity.visibility(),
                GitHubRepositoryStatus.valueOf(entity.bindingStatus()),
                entity.apiCredentialRef() != null && !entity.apiCredentialRef().isBlank(),
                entity.webhookSecretRef() != null && !entity.webhookSecretRef().isBlank(),
                entity.lastSyncedAt(),
                entity.lastVerifiedAt(),
                entity.createdBy(),
                entity.createdAt(),
                entity.updatedAt(),
                entity.version()
        );
    }
}
