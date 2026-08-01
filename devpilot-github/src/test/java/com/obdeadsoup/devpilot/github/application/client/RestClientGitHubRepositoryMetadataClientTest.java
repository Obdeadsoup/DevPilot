package com.obdeadsoup.devpilot.github.application.client;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RestClientGitHubRepositoryMetadataClientTest {

    private static final String CREDENTIAL = "DEVPILOT_GITHUB_API_TOKEN_TEST";
    private final GitHubApiHttpExecutor executor = mock(GitHubApiHttpExecutor.class);
    private final RestClientGitHubRepositoryMetadataClient client =
            new RestClientGitHubRepositoryMetadataClient(executor);

    @Test
    void mapsOnlyTrustedRepositoryFieldsAndPreservesHeaderMetadata() {
        RestClientGitHubRepositoryMetadataClient.RepositoryResponse raw =
                new RestClientGitHubRepositoryMetadataClient.RepositoryResponse(
                        123_456L,
                        new RestClientGitHubRepositoryMetadataClient.OwnerResponse("octo-org"),
                        "devpilot",
                        "octo-org/devpilot",
                        "https://github.com/octo-org/devpilot",
                        "main",
                        "private"
                );
        when(executor.get(
                eq("repository.metadata.get"),
                eq("/repos/{owner}/{repo}"),
                eq(List.of("repos", "octo-org", "devpilot")),
                eq(CREDENTIAL),
                eq(GitHubConditionalRequest.none()),
                eq(RestClientGitHubRepositoryMetadataClient.RepositoryResponse.class)
        )).thenReturn(rawResponse(raw, false, "\"etag-v1\""));

        GitHubApiResponse<VerifiedGitHubRepository> response = client.getRepository(
                "octo-org", "devpilot", CREDENTIAL
        );

        assertThat(response.body()).isEqualTo(new VerifiedGitHubRepository(
                123_456L,
                "octo-org",
                "devpilot",
                "octo-org/devpilot",
                "https://github.com/octo-org/devpilot",
                "main",
                "private"
        ));
        assertThat(response.etag()).isEqualTo("\"etag-v1\"");
        assertThat(response.lastModified()).isEqualTo(Instant.parse("2026-08-01T00:00:00Z"));
    }

    @Test
    void forwardsConditionalRequestAndKeeps304WithoutBody() {
        GitHubConditionalRequest conditional = new GitHubConditionalRequest(
                "\"etag-v1\"", Instant.parse("2026-07-31T00:00:00Z")
        );
        when(executor.get(
                eq("repository.metadata.get"),
                eq("/repos/{owner}/{repo}"),
                eq(List.of("repos", "octo", "demo")),
                eq(CREDENTIAL),
                eq(conditional),
                eq(RestClientGitHubRepositoryMetadataClient.RepositoryResponse.class)
        )).thenReturn(rawResponse(null, true, "\"etag-v1\""));

        GitHubApiResponse<VerifiedGitHubRepository> response = client.getRepository(
                "octo", "demo", CREDENTIAL, conditional
        );

        assertThat(response.notModified()).isTrue();
        assertThat(response.body()).isNull();
        verify(executor).get(
                "repository.metadata.get",
                "/repos/{owner}/{repo}",
                List.of("repos", "octo", "demo"),
                CREDENTIAL,
                conditional,
                RestClientGitHubRepositoryMetadataClient.RepositoryResponse.class
        );
    }

    @Test
    void missingStableRepositoryIdIsMalformedResponse() {
        RestClientGitHubRepositoryMetadataClient.RepositoryResponse raw =
                new RestClientGitHubRepositoryMetadataClient.RepositoryResponse(
                        null,
                        new RestClientGitHubRepositoryMetadataClient.OwnerResponse("octo"),
                        "demo",
                        "octo/demo",
                        "https://github.com/octo/demo",
                        "main",
                        "public"
                );
        when(executor.get(
                any(), any(), any(), any(), any(),
                eq(RestClientGitHubRepositoryMetadataClient.RepositoryResponse.class)
        )).thenReturn(rawResponse(raw, false, null));

        assertThatThrownBy(() -> client.getRepository("octo", "demo", CREDENTIAL))
                .isInstanceOfSatisfying(GitHubApiException.class, exception -> {
                    assertThat(exception.failureType())
                            .isEqualTo(GitHubApiFailureType.MALFORMED_RESPONSE);
                    assertThat(exception.requestId()).isEqualTo("request-id");
                });
    }

    private GitHubApiResponse<RestClientGitHubRepositoryMetadataClient.RepositoryResponse> rawResponse(
            RestClientGitHubRepositoryMetadataClient.RepositoryResponse body,
            boolean notModified,
            String etag
    ) {
        return new GitHubApiResponse<>(
                notModified ? 304 : 200,
                body,
                notModified,
                etag,
                Instant.parse("2026-08-01T00:00:00Z"),
                new GitHubRateLimitSnapshot(
                        5_000L, 4_999L, 1L, Instant.parse("2026-08-01T01:00:00Z"),
                        "core", null, "request-id"
                ),
                GitHubPageCursor.empty()
        );
    }
}
