package com.obdeadsoup.devpilot.github.application.client;

public record VerifiedGitHubRepository(
        long githubRepositoryId,
        String ownerLogin,
        String repositoryName,
        String fullName,
        String htmlUrl,
        String defaultBranch,
        String visibility
) {
}
