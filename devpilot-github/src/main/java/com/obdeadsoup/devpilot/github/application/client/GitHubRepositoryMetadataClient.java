package com.obdeadsoup.devpilot.github.application.client;

/** 使用统一 HTTP Executor 获取可信 Repository Metadata 的业务 Client 边界。 */
public interface GitHubRepositoryMetadataClient {

    GitHubApiResponse<VerifiedGitHubRepository> getRepository(
            String owner,
            String repositoryName,
            String apiCredentialReference,
            GitHubConditionalRequest conditionalRequest
    );

    default GitHubApiResponse<VerifiedGitHubRepository> getRepository(
            String owner,
            String repositoryName,
            String apiCredentialReference
    ) {
        return getRepository(
                owner,
                repositoryName,
                apiCredentialReference,
                GitHubConditionalRequest.none()
        );
    }
}
