package com.obdeadsoup.devpilot.github.application.command;

import com.obdeadsoup.devpilot.github.domain.GitHubPullRequestReviewStatus;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;

import java.time.LocalDateTime;

public record UpsertGitHubPullRequestReviewCommand(
        long workspaceId,
        long projectId,
        long repositoryBindingId,
        long githubRepositoryId,
        String repositoryFullName,
        int pullRequestNumber,
        long githubReviewId,
        Long reviewerGitHubUserId,
        String reviewerLogin,
        GitHubPullRequestReviewStatus status,
        String body,
        String commitSha,
        String htmlUrl,
        LocalDateTime submittedAt,
        LocalDateTime githubUpdatedAt,
        GitHubSnapshotSource source,
        String sourceEventId,
        String webhookAction,
        String contentHash
) {
}
