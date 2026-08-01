package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Repository Metadata 的 GitHub 业务 Client。
 *
 * <p>本类只描述 endpoint 与校验 Repository 业务字段；Authorization、超时、状态码、Rate Limit、
 * Retry、重定向、日志和指标全部交给 {@link GitHubApiHttpExecutor}，避免每个业务 Client 重复实现。</p>
 */
@Component
public class RestClientGitHubRepositoryMetadataClient implements GitHubRepositoryMetadataClient {

    static final String OPERATION = "repository.metadata.get";
    static final String ENDPOINT_TEMPLATE = "/repos/{owner}/{repo}";

    private final GitHubApiHttpExecutor httpExecutor;

    public RestClientGitHubRepositoryMetadataClient(GitHubApiHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    /**
     * 查询 Repository，并保留 ETag、Last-Modified、Rate Limit 和 304 语义。
     *
     * @param owner 已通过 Repository Reference 校验的 Owner
     * @param repositoryName 已通过 Repository Reference 校验的仓库名
     * @param apiCredentialReference 受白名单约束的 Credential Reference，不是 Token
     * @param conditionalRequest 刷新时使用的校验器；首次绑定传空条件
     * @return 200 时包含可信 Repository，304 时 body 为空且 notModified=true
     * @throws GitHubApiException HTTP、网络、限流、凭据或关键字段错误
     */
    @Override
    public GitHubApiResponse<VerifiedGitHubRepository> getRepository(
            String owner,
            String repositoryName,
            String apiCredentialReference,
            GitHubConditionalRequest conditionalRequest
    ) {
        GitHubApiResponse<RepositoryResponse> response = httpExecutor.get(
                OPERATION,
                ENDPOINT_TEMPLATE,
                List.of("repos", owner, repositoryName),
                apiCredentialReference,
                conditionalRequest,
                RepositoryResponse.class
        );
        if (response.notModified()) {
            return mapped(response, null);
        }
        return mapped(response, verified(response.body(), response));
    }

    private GitHubApiResponse<VerifiedGitHubRepository> mapped(
            GitHubApiResponse<RepositoryResponse> source,
            VerifiedGitHubRepository repository
    ) {
        return new GitHubApiResponse<>(
                source.httpStatus(),
                repository,
                source.notModified(),
                source.etag(),
                source.lastModified(),
                source.rateLimit(),
                source.pageCursor()
        );
    }

    private VerifiedGitHubRepository verified(
            RepositoryResponse response,
            GitHubApiResponse<?> envelope
    ) {
        if (response == null
                || response.id() == null
                || response.id() <= 0
                || response.owner() == null
                || isBlank(response.owner().login())
                || isBlank(response.name())
                || isBlank(response.fullName())
                || isBlank(response.htmlUrl())
                || isBlank(response.visibility())) {
            throw new GitHubApiException(
                    GitHubApiFailureType.MALFORMED_RESPONSE,
                    false,
                    null,
                    envelope.httpStatus(),
                    "GitHub API returned repository metadata without required fields",
                    envelope.rateLimit().requestId(),
                    envelope.rateLimit()
            );
        }
        return new VerifiedGitHubRepository(
                response.id(),
                response.owner().login(),
                response.name(),
                response.fullName(),
                response.htmlUrl(),
                response.defaultBranch(),
                response.visibility()
        );
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static record RepositoryResponse(
            Long id,
            OwnerResponse owner,
            String name,
            @JsonProperty("full_name") String fullName,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("default_branch") String defaultBranch,
            String visibility
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static record OwnerResponse(String login) {
    }
}
