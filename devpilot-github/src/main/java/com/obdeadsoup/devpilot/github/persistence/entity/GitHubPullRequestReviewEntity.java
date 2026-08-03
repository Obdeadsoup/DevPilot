package com.obdeadsoup.devpilot.github.persistence.entity;

import java.time.LocalDateTime;

public record GitHubPullRequestReviewEntity(
        long id, long workspaceId, long projectId, long repositoryBindingId,
        long githubRepositoryId, long pullRequestId, long githubReviewId,
        Long reviewerGitHubUserId, String reviewerLogin, String state, String body,
        String commitSha, String htmlUrl, LocalDateTime submittedAt,
        LocalDateTime githubUpdatedAt, String firstSeenSource, String contentHash,
        LocalDateTime createdAt, LocalDateTime updatedAt, long version
) {
}
