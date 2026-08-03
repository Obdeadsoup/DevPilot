package com.obdeadsoup.devpilot.github.application.command;

import com.obdeadsoup.devpilot.github.domain.GitHubPullRequestStatus;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;

import java.time.LocalDateTime;

public record UpsertGitHubPullRequestCommand(
        long workspaceId,
        long projectId,
        long repositoryBindingId,
        long githubRepositoryId,
        String repositoryFullName,
        long githubPullRequestId,
        Long githubIssueId,
        int pullRequestNumber,
        String title,
        String body,
        GitHubPullRequestStatus status,
        boolean draft,
        Long authorGitHubUserId,
        String authorLogin,
        String headRef,
        String headSha,
        String baseRef,
        String baseSha,
        String mergeCommitSha,
        String requestedReviewersJson,
        String assigneeSummaryJson,
        String labelsJson,
        String htmlUrl,
        LocalDateTime githubCreatedAt,
        LocalDateTime githubUpdatedAt,
        LocalDateTime githubClosedAt,
        LocalDateTime githubMergedAt,
        GitHubSnapshotSource source,
        String sourceEventId,
        String webhookAction,
        String contentHash
) {
}
