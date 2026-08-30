package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/** 分支读取 Client，复用统一 GitHub HTTP 执行链，因此不会泄漏 Token 或绕过限流策略。 */
@Component
public class RestClientGitHubBranchClient implements GitHubBranchClient {
    private static final String OPERATION = "repository.branches.list";
    private static final String ENDPOINT = "/repos/{owner}/{repo}/branches";
    private final GitHubApiHttpExecutor executor;

    public RestClientGitHubBranchClient(GitHubApiHttpExecutor executor) { this.executor = executor; }

    @Override
    public List<GitHubBranch> listBranches(String owner, String repositoryName, String apiCredentialReference) {
        GitHubApiResponse<BranchResponse[]> response = executor.get(OPERATION, ENDPOINT,
                List.of("repos", owner, repositoryName, "branches"), Map.of("per_page", 100),
                apiCredentialReference, GitHubConditionalRequest.none(), BranchResponse[].class);
        if (response.body() == null) {
            throw new GitHubApiException(GitHubApiFailureType.MALFORMED_RESPONSE, false, null,
                    response.httpStatus(), "GitHub API returned branches without a response body", null, response.rateLimit());
        }
        return java.util.Arrays.stream(response.body()).map(branch -> {
            if (branch == null || branch.name() == null || branch.name().isBlank() || branch.commit() == null
                    || branch.commit().sha() == null || !branch.commit().sha().matches("[0-9a-fA-F]{40}")) {
                throw new GitHubApiException(GitHubApiFailureType.MALFORMED_RESPONSE, false, null,
                        response.httpStatus(), "GitHub API returned an invalid branch", null, response.rateLimit());
            }
            return new GitHubBranch(branch.name(), branch.commit().sha());
        }).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private record BranchResponse(String name, CommitResponse commit) { }
    @JsonIgnoreProperties(ignoreUnknown = true)
    private record CommitResponse(@JsonProperty("sha") String sha) { }
}
