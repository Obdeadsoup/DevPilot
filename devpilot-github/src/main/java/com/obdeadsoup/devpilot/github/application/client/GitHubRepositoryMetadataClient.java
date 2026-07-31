package com.obdeadsoup.devpilot.github.application.client;

public interface GitHubRepositoryMetadataClient {

    VerifiedGitHubRepository getRepository(String owner, String repositoryName, String apiToken);
}
