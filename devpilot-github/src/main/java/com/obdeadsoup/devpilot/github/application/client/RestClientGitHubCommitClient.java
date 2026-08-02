package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * GitHub List Commits 的业务 Client，只保留对账需要的小型元数据。
 * Patch、文件列表、完整响应和私有响应正文不会离开统一 HTTP 执行层。
 */
@Component
public class RestClientGitHubCommitClient implements GitHubCommitClient {

    static final String OPERATION = "repository.commits.list";
    static final String ENDPOINT_TEMPLATE = "/repos/{owner}/{repo}/commits";

    private final GitHubApiHttpExecutor httpExecutor;

    public RestClientGitHubCommitClient(GitHubApiHttpExecutor httpExecutor) {
        this.httpExecutor = httpExecutor;
    }

    @Override
    public GitHubPage<GitHubCommit> listCommits(
            String owner,
            String repositoryName,
            Instant since,
            int perPage,
            String apiCredentialReference,
            GitHubPageCursor cursor
    ) {
        GitHubApiResponse<CommitResponse[]> response;
        if (cursor != null && cursor.hasNext()) {
            response = httpExecutor.getPage(
                    OPERATION,
                    ENDPOINT_TEMPLATE,
                    cursor,
                    apiCredentialReference,
                    CommitResponse[].class
            );
        } else {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("since", since.toString());
            query.put("per_page", perPage);
            response = httpExecutor.get(
                    OPERATION,
                    ENDPOINT_TEMPLATE,
                    List.of("repos", owner, repositoryName, "commits"),
                    query,
                    apiCredentialReference,
                    GitHubConditionalRequest.none(),
                    CommitResponse[].class
            );
        }
        if (response.body() == null) {
            throw malformed(response, "GitHub API returned commits without a response body");
        }
        List<GitHubCommit> commits = Arrays.stream(response.body())
                .map(item -> toCommit(item, response))
                .toList();
        return new GitHubPage<>(commits, response.pageCursor());
    }

    private GitHubCommit toCommit(CommitResponse response, GitHubApiResponse<?> envelope) {
        if (response == null
                || response.sha() == null
                || !response.sha().matches("[0-9a-f]{40}")
                || response.commit() == null) {
            throw malformed(envelope, "GitHub API returned commit data without a valid SHA");
        }
        GitIdentity author = response.commit().author();
        GitIdentity committer = response.commit().committer();
        Instant authoredAt = parseInstant(author == null ? null : author.date(), envelope);
        Instant committedAt = parseInstant(committer == null ? null : committer.date(), envelope);
        if (committedAt == null) {
            committedAt = authoredAt;
        }
        if (committedAt == null) {
            throw malformed(envelope, "GitHub API returned commit data without a timestamp");
        }
        return new GitHubCommit(
                response.sha(),
                truncate(response.commit().message(), 2000),
                truncate(author == null ? null : author.name(), 255),
                truncate(author == null ? null : author.email(), 320),
                response.author() == null ? null : response.author().id(),
                truncate(response.author() == null ? null : response.author().login(), 100),
                authoredAt,
                committedAt,
                truncate(response.htmlUrl(), 500)
        );
    }

    private Instant parseInstant(String value, GitHubApiResponse<?> envelope) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException exception) {
            throw malformed(envelope, "GitHub API returned commit data with an invalid timestamp");
        }
    }

    private GitHubApiException malformed(GitHubApiResponse<?> response, String message) {
        return new GitHubApiException(
                GitHubApiFailureType.MALFORMED_RESPONSE,
                false,
                null,
                response.httpStatus(),
                message,
                response.rateLimit() == null ? null : response.rateLimit().requestId(),
                response.rateLimit()
        );
    }

    private String truncate(String value, int maximumLength) {
        return value == null || value.length() <= maximumLength
                ? value
                : value.substring(0, maximumLength);
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static record CommitResponse(
            String sha,
            GitCommit commit,
            GitHubAuthor author,
            @JsonProperty("html_url") String htmlUrl
    ) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static record GitCommit(String message, GitIdentity author, GitIdentity committer) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static record GitIdentity(String name, String email, String date) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    static record GitHubAuthor(Long id, String login) {
    }
}
