package com.obdeadsoup.devpilot.github.application.client;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubRepositoryErrorCode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class RestClientGitHubRepositoryMetadataClientTest {

    private static final String TOKEN = "private-api-token-value";
    private static final String REPOSITORY_JSON = """
            {
              "id": 123456,
              "owner": {"login": "octo-org"},
              "name": "devpilot",
              "full_name": "octo-org/devpilot",
              "html_url": "https://github.com/octo-org/devpilot",
              "default_branch": "main",
              "visibility": "private",
              "ignored": "not parsed into the verified model"
            }
            """;

    @Test
    void fetchesAndMapsOnlyTrustedRepositoryMetadataWithRequiredHeaders() {
        Harness harness = harness();
        harness.server().expect(once(), requestTo("https://api.github.com/repos/octo-org/devpilot"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header(HttpHeaders.USER_AGENT, "DevPilot/0.0.1"))
                .andRespond(withSuccess(REPOSITORY_JSON, MediaType.APPLICATION_JSON));

        VerifiedGitHubRepository repository = harness.client()
                .getRepository("octo-org", "devpilot", TOKEN);

        assertThat(repository).isEqualTo(new VerifiedGitHubRepository(
                123456,
                "octo-org",
                "devpilot",
                "octo-org/devpilot",
                "https://github.com/octo-org/devpilot",
                "main",
                "private"
        ));
        assertThat(repository.toString()).doesNotContain(TOKEN);
        harness.server().verify();
    }

    @ParameterizedTest
    @MethodSource("httpErrors")
    void mapsGitHubHttpErrorsToStableSafeErrors(
            HttpStatus status,
            GitHubRepositoryErrorCode expectedError
    ) {
        Harness harness = harness();
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"private upstream detail " + TOKEN + "\"}"));

        assertThatThrownBy(() -> harness.client().getRepository("octo", "demo", TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode()).isEqualTo(expectedError);
                    assertThat(exception.getMessage()).doesNotContain(TOKEN, "private upstream detail");
                });
        harness.server().verify();
    }

    @Test
    void mapsTimeoutToSafeUnavailableError() {
        Harness harness = harness();
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andRespond(request -> {
                    throw new ResourceAccessException("timeout containing " + TOKEN);
                });

        assertThatThrownBy(() -> harness.client().getRepository("octo", "demo", TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception -> {
                    assertThat(exception.errorCode())
                            .isEqualTo(GitHubRepositoryErrorCode.GITHUB_API_UNAVAILABLE);
                    assertThat(exception.getMessage()).doesNotContain(TOKEN, "timeout containing");
                });
        harness.server().verify();
    }

    @Test
    void rejectsResponseWithoutStableRepositoryId() {
        Harness harness = harness();
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andRespond(withSuccess("""
                        {
                          "owner":{"login":"octo"},
                          "name":"demo",
                          "full_name":"octo/demo",
                          "html_url":"https://github.com/octo/demo",
                          "visibility":"public"
                        }
                        """, MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> harness.client().getRepository("octo", "demo", TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(GitHubRepositoryErrorCode.GITHUB_API_RESPONSE_INVALID));
        harness.server().verify();
    }

    @Test
    void encodesEveryRepositoryPathSegment() {
        Harness harness = harness();
        harness.server().expect(requestTo("https://api.github.com/repos/octo/repo%20name"))
                .andRespond(withSuccess(REPOSITORY_JSON, MediaType.APPLICATION_JSON));

        harness.client().getRepository("octo", "repo name", TOKEN);

        harness.server().verify();
    }

    @Test
    void cannotTurnClientInputIntoAnArbitraryApiHost() {
        Harness harness = harness();
        harness.server().expect(request -> {
                    assertThat(request.getURI().getScheme()).isEqualTo("https");
                    assertThat(request.getURI().getHost()).isEqualTo("api.github.com");
                    assertThat(request.getURI().getRawPath())
                            .isEqualTo("/repos/https:%2F%2Fevil.example/demo");
                })
                .andRespond(withSuccess(REPOSITORY_JSON, MediaType.APPLICATION_JSON));

        harness.client().getRepository("https://evil.example", "demo", TOKEN);

        harness.server().verify();
    }

    @Test
    void followsOneRenameRedirectOnlyWhenItStaysOnTheFixedGitHubApiHost() {
        Harness harness = harness();
        harness.server().expect(requestTo("https://api.github.com/repos/octo/old-name"))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
                        .location(URI.create("https://api.github.com/repositories/123456")));
        harness.server().expect(requestTo("https://api.github.com/repositories/123456"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess(REPOSITORY_JSON, MediaType.APPLICATION_JSON));

        VerifiedGitHubRepository repository = harness.client()
                .getRepository("octo", "old-name", TOKEN);

        assertThat(repository.githubRepositoryId()).isEqualTo(123456L);
        harness.server().verify();
    }

    @Test
    void rejectsRedirectAwayFromTheFixedGitHubApiHost() {
        Harness harness = harness();
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
                        .location(URI.create("https://evil.example/repositories/123456")));

        assertThatThrownBy(() -> harness.client().getRepository("octo", "demo", TOKEN))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(GitHubRepositoryErrorCode.GITHUB_REPOSITORY_NOT_ACCESSIBLE));
        harness.server().verify();
    }

    private static Stream<Arguments> httpErrors() {
        return Stream.of(
                Arguments.of(
                        HttpStatus.UNAUTHORIZED,
                        GitHubRepositoryErrorCode.GITHUB_API_AUTHENTICATION_FAILED
                ),
                Arguments.of(HttpStatus.FORBIDDEN, GitHubRepositoryErrorCode.GITHUB_API_FORBIDDEN),
                Arguments.of(HttpStatus.NOT_FOUND, GitHubRepositoryErrorCode.GITHUB_REPOSITORY_NOT_ACCESSIBLE),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, GitHubRepositoryErrorCode.GITHUB_API_RATE_LIMITED),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, GitHubRepositoryErrorCode.GITHUB_API_UNAVAILABLE)
        );
    }

    private Harness harness() {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl(RestClientGitHubRepositoryMetadataClient.API_BASE_URL);
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        return new Harness(
                new RestClientGitHubRepositoryMetadataClient(builder.build()),
                server
        );
    }

    private record Harness(
            RestClientGitHubRepositoryMetadataClient client,
            MockRestServiceServer server
    ) {
    }
}
