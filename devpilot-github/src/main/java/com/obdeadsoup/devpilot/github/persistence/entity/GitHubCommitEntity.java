package com.obdeadsoup.devpilot.github.persistence.entity;

import java.time.LocalDateTime;

public record GitHubCommitEntity(
        long id,
        long workspaceId,
        long projectId,
        long repositoryBindingId,
        long githubRepositoryId,
        String commitSha,
        String message,
        String authorName,
        String authorEmail,
        Long authorGitHubUserId,
        String authorLogin,
        LocalDateTime committedAt,
        String htmlUrl,
        String firstSeenSource,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version
) {
}
