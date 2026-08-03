package com.obdeadsoup.devpilot.github.application.command;

import com.obdeadsoup.devpilot.github.domain.GitHubIssueStatus;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;

import java.time.LocalDateTime;

public record UpsertGitHubIssueCommand(
        long workspaceId,
        long projectId,
        long repositoryBindingId,
        long githubRepositoryId,
        String repositoryFullName,
        long githubIssueId,
        int issueNumber,
        String title,
        String body,
        GitHubIssueStatus status,
        String stateReason,
        Long authorGitHubUserId,
        String authorLogin,
        String assigneeSummaryJson,
        String labelsJson,
        String htmlUrl,
        LocalDateTime githubCreatedAt,
        LocalDateTime githubUpdatedAt,
        LocalDateTime githubClosedAt,
        GitHubSnapshotSource source,
        String sourceEventId,
        String webhookAction,
        String contentHash
) {
}
