package com.obdeadsoup.devpilot.github.persistence.entity;

import java.time.LocalDateTime;

public record GitHubIssueEntity(
        long id, long workspaceId, long projectId, long repositoryBindingId,
        long githubRepositoryId, long githubIssueId, int issueNumber,
        String title, String body, String state, String stateReason,
        Long authorGitHubUserId, String authorLogin, String assigneeSummaryJson,
        String labelsJson, String htmlUrl, LocalDateTime githubCreatedAt,
        LocalDateTime githubUpdatedAt, LocalDateTime githubClosedAt,
        String firstSeenSource, String contentHash, LocalDateTime createdAt,
        LocalDateTime updatedAt, long version
) {
}
