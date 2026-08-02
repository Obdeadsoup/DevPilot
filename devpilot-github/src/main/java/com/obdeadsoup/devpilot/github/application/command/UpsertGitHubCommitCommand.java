package com.obdeadsoup.devpilot.github.application.command;

import com.obdeadsoup.devpilot.github.domain.GitHubCommitSource;

import java.time.LocalDateTime;

public record UpsertGitHubCommitCommand(
        long workspaceId,
        long projectId,
        long repositoryBindingId,
        long githubRepositoryId,
        String repositoryFullName,
        String commitSha,
        String message,
        String authorName,
        String authorEmail,
        Long authorGitHubUserId,
        String authorLogin,
        LocalDateTime committedAt,
        String htmlUrl,
        GitHubCommitSource source
) {
}
