package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pull Requests API Client，保留真正 PR ID、draft、mergedAt 和 head/base。 */
@Component
public class RestClientGitHubPullRequestClient
        extends GitHubSnapshotClientSupport implements GitHubPullRequestClient {

    static final String OPERATION = "repository.pull_requests.list";
    static final String ENDPOINT = "/repos/{owner}/{repo}/pulls";

    public RestClientGitHubPullRequestClient(GitHubApiHttpExecutor executor, ObjectMapper mapper) {
        super(executor, mapper);
    }

    @Override
    public GitHubPage<GitHubPullRequest> listPullRequests(
            String owner,
            String repository,
            int perPage,
            String credentialRef,
            GitHubPageCursor cursor) {
        GitHubApiResponse<PullResponse[]> response;
        if (cursor != null && cursor.hasNext()) {
            response = executor.getPage(OPERATION, ENDPOINT, cursor, credentialRef, PullResponse[].class);
        } else {
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("state", "all");
            query.put("sort", "updated");
            query.put("direction", "desc");
            query.put("per_page", perPage);
            response = executor.get(
                    OPERATION,
                    ENDPOINT,
                    List.of("repos", owner, repository, "pulls"),
                    query,
                    credentialRef,
                    GitHubConditionalRequest.none(),
                    PullResponse[].class);
        }
        if (response.body() == null) {
            throw malformed(response, "GitHub API returned pull requests without body");
        }
        return new GitHubPage<>(
                Arrays.stream(response.body()).map(item -> toPull(item, response)).toList(),
                response.pageCursor());
    }

    private GitHubPullRequest toPull(PullResponse pull, GitHubApiResponse<?> response) {
        if (pull == null
                || pull.id() == null
                || pull.id() <= 0
                || pull.number() == null
                || pull.number() <= 0
                || pull.title() == null
                || pull.state() == null
                || pull.head() == null
                || pull.base() == null) {
            throw malformed(response, "GitHub API pull request identity is invalid");
        }
        return new GitHubPullRequest(
                pull.id(),
                pull.number(),
                pull.title(),
                pull.body(),
                pull.state(),
                Boolean.TRUE.equals(pull.draft()),
                pull.user() == null ? null : pull.user().id(),
                pull.user() == null ? null : pull.user().login(),
                pull.head().ref(),
                pull.head().sha(),
                pull.base().ref(),
                pull.base().sha(),
                pull.mergeCommitSha(),
                json(logins(pull.requestedReviewers()), response),
                json(logins(pull.assignees()), response),
                json(labels(pull.labels()), response),
                pull.htmlUrl(),
                requiredTime(pull.createdAt(), response),
                requiredTime(pull.updatedAt(), response),
                time(pull.closedAt(), response),
                time(pull.mergedAt(), response));
    }

    private List<String> logins(List<User> users) {
        return users == null ? List.of() : users.stream().map(User::login).toList();
    }

    private List<String> labels(List<Label> labels) {
        return labels == null ? List.of() : labels.stream().map(Label::name).toList();
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PullResponse(
            Long id,
            Integer number,
            String title,
            String body,
            String state,
            Boolean draft,
            User user,
            Ref head,
            Ref base,
            @JsonProperty("merge_commit_sha") String mergeCommitSha,
            @JsonProperty("requested_reviewers") List<User> requestedReviewers,
            List<User> assignees,
            List<Label> labels,
            @JsonProperty("html_url") String htmlUrl,
            @JsonProperty("created_at") String createdAt,
            @JsonProperty("updated_at") String updatedAt,
            @JsonProperty("closed_at") String closedAt,
            @JsonProperty("merged_at") String mergedAt) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record User(Long id, String login) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Label(String name) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Ref(String ref, String sha) {
    }
}
