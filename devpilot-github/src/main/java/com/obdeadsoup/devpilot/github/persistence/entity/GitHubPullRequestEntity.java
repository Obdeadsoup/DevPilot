package com.obdeadsoup.devpilot.github.persistence.entity;

import java.time.LocalDateTime;

public record GitHubPullRequestEntity(
        long id, long workspaceId, long projectId, long repositoryBindingId,
        long githubRepositoryId, long githubPullRequestId, Long githubIssueId,
        int pullRequestNumber, String title, String body, String status, boolean draft,
        Long authorGitHubUserId, String authorLogin, String headRef, String headSha,
        String baseRef, String baseSha, String mergeCommitSha,
        String requestedReviewersJson, String assigneeSummaryJson, String labelsJson,
        String htmlUrl, LocalDateTime githubCreatedAt, LocalDateTime githubUpdatedAt,
        LocalDateTime githubClosedAt, LocalDateTime githubMergedAt,
        LocalDateTime reviewsSyncedAt, String firstSeenSource, String contentHash,
        LocalDateTime createdAt, LocalDateTime updatedAt, long version
) {
}
