package com.obdeadsoup.devpilot.github.application.client;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestClientGitHubCommitClientTest {

    private static final String CREDENTIAL = "DEVPILOT_GITHUB_API_TOKEN_TEST";
    private static final Instant SINCE = Instant.parse("2026-07-25T00:00:00Z");

    private final GitHubApiHttpExecutor executor = mock(GitHubApiHttpExecutor.class);
    private final RestClientGitHubCommitClient client = new RestClientGitHubCommitClient(executor);

    @Test
    void mapsSinglePageAndSendsSinceAndPerPage() {
        when(executor.get(
                eq(RestClientGitHubCommitClient.OPERATION),
                eq(RestClientGitHubCommitClient.ENDPOINT_TEMPLATE),
                eq(List.of("repos", "octo", "demo", "commits")),
                any(Map.class),
                eq(CREDENTIAL),
                eq(GitHubConditionalRequest.none()),
                eq(RestClientGitHubCommitClient.CommitResponse[].class)
        )).thenReturn(response(new RestClientGitHubCommitClient.CommitResponse[]{validCommit()}, cursor(null)));

        GitHubPage<GitHubCommit> page = client.listCommits(
                "octo", "demo", SINCE, 50, CREDENTIAL, GitHubPageCursor.empty()
        );

        assertThat(page.items()).singleElement().satisfies(commit -> {
            assertThat(commit.sha()).isEqualTo("a".repeat(40));
            assertThat(commit.message()).isEqualTo("Ship reconciliation");
            assertThat(commit.authorEmail()).isEqualTo("octo@example.com");
            assertThat(commit.authorLogin()).isEqualTo("octocat");
            assertThat(commit.authoredAt()).isEqualTo(Instant.parse("2026-08-01T09:00:00Z"));
            assertThat(commit.committedAt()).isEqualTo(Instant.parse("2026-08-01T10:00:00Z"));
        });
        ArgumentCaptor<Map<String, Object>> query = ArgumentCaptor.forClass(Map.class);
        verify(executor).get(
                eq(RestClientGitHubCommitClient.OPERATION),
                eq(RestClientGitHubCommitClient.ENDPOINT_TEMPLATE),
                eq(List.of("repos", "octo", "demo", "commits")),
                query.capture(),
                eq(CREDENTIAL),
                eq(GitHubConditionalRequest.none()),
                eq(RestClientGitHubCommitClient.CommitResponse[].class)
        );
        assertThat(query.getValue()).containsEntry("since", SINCE.toString()).containsEntry("per_page", 50);
    }

    @Test
    void followsLinkCursorForSecondPageWithoutIncrementingPageNumber() {
        GitHubPageCursor next = cursor("https://api.github.com/repositories/1/commits?after=opaque");
        when(executor.getPage(
                any(), any(), eq(next), eq(CREDENTIAL),
                eq(RestClientGitHubCommitClient.CommitResponse[].class)
        )).thenReturn(response(new RestClientGitHubCommitClient.CommitResponse[]{validCommit()}, cursor(null)));

        GitHubPage<GitHubCommit> page = client.listCommits(
                "ignored", "ignored", SINCE, 100, CREDENTIAL, next
        );

        assertThat(page.items()).hasSize(1);
        verify(executor).getPage(
                RestClientGitHubCommitClient.OPERATION,
                RestClientGitHubCommitClient.ENDPOINT_TEMPLATE,
                next,
                CREDENTIAL,
                RestClientGitHubCommitClient.CommitResponse[].class
        );
    }

    @Test
    void acceptsEmptyResult() {
        stubInitial(response(new RestClientGitHubCommitClient.CommitResponse[0], cursor(null)));

        assertThat(client.listCommits("octo", "demo", SINCE, 100, CREDENTIAL, null).items()).isEmpty();
    }

    @Test
    void rejectsJsonItemWithoutSha() {
        RestClientGitHubCommitClient.CommitResponse invalid =
                new RestClientGitHubCommitClient.CommitResponse(null, validCommit().commit(), null, null);
        stubInitial(response(new RestClientGitHubCommitClient.CommitResponse[]{invalid}, cursor(null)));

        assertThatThrownBy(() -> client.listCommits("octo", "demo", SINCE, 100, CREDENTIAL, null))
                .isInstanceOfSatisfying(GitHubApiException.class, exception ->
                        assertThat(exception.failureType()).isEqualTo(GitHubApiFailureType.MALFORMED_RESPONSE));
    }

    @Test
    void propagatesRateLimit503AndTimeoutClassifications() {
        for (GitHubApiFailureType failureType : List.of(
                GitHubApiFailureType.RATE_LIMITED,
                GitHubApiFailureType.TRANSIENT_SERVER_ERROR,
                GitHubApiFailureType.NETWORK_ERROR
        )) {
            GitHubApiHttpExecutor localExecutor = mock(GitHubApiHttpExecutor.class);
            RestClientGitHubCommitClient localClient = new RestClientGitHubCommitClient(localExecutor);
            when(localExecutor.get(any(), any(), any(), any(Map.class), any(), any(), any()))
                    .thenThrow(apiFailure(failureType));

            assertThatThrownBy(() -> localClient.listCommits(
                    "octo", "demo", SINCE, 100, CREDENTIAL, null
            )).isInstanceOfSatisfying(GitHubApiException.class, exception ->
                    assertThat(exception.failureType()).isEqualTo(failureType));
        }
    }

    @Test
    void maliciousNextHostIsRejectedByExecutorBoundary() {
        GitHubPageCursor malicious = cursor("https://evil.example/commits?after=secret");
        when(executor.getPage(any(), any(), eq(malicious), any(), any()))
                .thenThrow(apiFailure(GitHubApiFailureType.VALIDATION));

        assertThatThrownBy(() -> client.listCommits(
                "octo", "demo", SINCE, 100, CREDENTIAL, malicious
        )).isInstanceOfSatisfying(GitHubApiException.class, exception ->
                assertThat(exception.failureType()).isEqualTo(GitHubApiFailureType.VALIDATION));
    }

    private void stubInitial(GitHubApiResponse<RestClientGitHubCommitClient.CommitResponse[]> response) {
        when(executor.get(any(), any(), any(), any(Map.class), any(), any(), any())).thenReturn(response);
    }

    private GitHubApiResponse<RestClientGitHubCommitClient.CommitResponse[]> response(
            RestClientGitHubCommitClient.CommitResponse[] body,
            GitHubPageCursor cursor
    ) {
        return new GitHubApiResponse<>(200, body, false, null, null,
                new GitHubRateLimitSnapshot(5000L, 4999L, 1L, null, "core", null, "request"), cursor);
    }

    private RestClientGitHubCommitClient.CommitResponse validCommit() {
        return new RestClientGitHubCommitClient.CommitResponse(
                "a".repeat(40),
                new RestClientGitHubCommitClient.GitCommit(
                        "Ship reconciliation",
                        new RestClientGitHubCommitClient.GitIdentity(
                                "Octo Cat", "octo@example.com", "2026-08-01T09:00:00Z"
                        ),
                        new RestClientGitHubCommitClient.GitIdentity(
                                "Octo Cat", "octo@example.com", "2026-08-01T10:00:00Z"
                        )
                ),
                new RestClientGitHubCommitClient.GitHubAuthor(7L, "octocat"),
                "https://github.com/octo/demo/commit/" + "a".repeat(40)
        );
    }

    private GitHubPageCursor cursor(String next) {
        return new GitHubPageCursor(next == null ? null : URI.create(next), null, null, null);
    }

    private GitHubApiException apiFailure(GitHubApiFailureType type) {
        return new GitHubApiException(
                type,
                type == GitHubApiFailureType.RATE_LIMITED
                        || type == GitHubApiFailureType.TRANSIENT_SERVER_ERROR
                        || type == GitHubApiFailureType.NETWORK_ERROR,
                type == GitHubApiFailureType.RATE_LIMITED
                        ? Instant.parse("2026-08-01T12:00:00Z") : null,
                null,
                "safe failure",
                "request",
                null
        );
    }
}
