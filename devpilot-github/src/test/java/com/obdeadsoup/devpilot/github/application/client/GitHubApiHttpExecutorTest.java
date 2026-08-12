package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.github.application.credential.GitHubAccessToken;
import com.obdeadsoup.devpilot.github.application.credential.GitHubAccessTokenProvider;
import com.obdeadsoup.devpilot.github.support.GitHubTestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.times;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

@ExtendWith(OutputCaptureExtension.class)
class GitHubApiHttpExecutorTest {

    private static final String TOKEN = "private-token-must-never-leak";
    private static final String CREDENTIAL = "DEVPILOT_GITHUB_API_TOKEN_TEST";
    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");

    @Test
    void sendsConfiguredHeadersDynamicBearerAndReturnsSafeHeaderMetadata(CapturedOutput output) {
        Harness harness = harness(1, Duration.ofSeconds(3));
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andExpect(method(HttpMethod.GET))
                .andExpect(header(HttpHeaders.ACCEPT, "application/vnd.github+json"))
                .andExpect(header("X-GitHub-Api-Version", "2022-11-28"))
                .andExpect(header(HttpHeaders.USER_AGENT, "DevPilot"))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess("{\"id\":123}", MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.ETAG, "\"etag-v1\"")
                        .header(HttpHeaders.LAST_MODIFIED, "Fri, 01 Aug 2025 00:00:00 GMT")
                        .header("X-RateLimit-Limit", "5000")
                        .header("X-RateLimit-Remaining", "4999")
                        .header("X-RateLimit-Used", "1")
                        .header("X-RateLimit-Reset", Long.toString(NOW.plusSeconds(60).getEpochSecond()))
                        .header("X-RateLimit-Resource", "core")
                        .header("X-GitHub-Request-Id", "request-123"));

        GitHubApiResponse<IdResponse> response = harness.executor().get(
                "repository.metadata.get",
                "/repos/{owner}/{repo}",
                List.of("repos", "octo", "demo"),
                CREDENTIAL,
                GitHubConditionalRequest.none(),
                IdResponse.class
        );

        assertThat(response.body().id()).isEqualTo(123L);
        assertThat(response.etag()).isEqualTo("\"etag-v1\"");
        assertThat(response.rateLimit().remaining()).isEqualTo(4_999L);
        assertThat(response.rateLimit().requestId()).isEqualTo("request-123");
        assertThat(output.getAll()).doesNotContain(TOKEN, CREDENTIAL, "Bearer");
        harness.server().verify();
    }

    @Test
    void headUsesTheSameSafeExecutionChainWithoutParsingABody() {
        Harness harness = harness(1, Duration.ofSeconds(3));
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andExpect(method(HttpMethod.HEAD))
                .andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer " + TOKEN))
                .andRespond(withSuccess());

        GitHubApiResponse<Void> response = harness.executor().head(
                "repository.metadata.head",
                "/repos/{owner}/{repo}",
                List.of("repos", "octo", "demo"),
                CREDENTIAL,
                GitHubConditionalRequest.none()
        );

        assertThat(response.httpStatus()).isEqualTo(200);
        assertThat(response.body()).isNull();
        harness.server().verify();
    }

    @ParameterizedTest
    @MethodSource("httpFailures")
    void classifiesHttpFailuresWithoutRetryWhenAttemptsAreOne(
            HttpStatus status,
            GitHubApiFailureType expected
    ) {
        Harness harness = harness(1, Duration.ofSeconds(3));
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andRespond(withStatus(status)
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"message\":\"private response " + TOKEN + "\"}"));

        assertThatThrownBy(() -> get(harness))
                .isInstanceOfSatisfying(GitHubApiException.class, exception -> {
                    assertThat(exception.failureType()).isEqualTo(expected);
                    assertThat(exception.getMessage()).doesNotContain(TOKEN, "private response");
                });
        harness.server().verify();
    }

    @Test
    void connectAndReadTimeoutsAreNetworkErrorsRetriedUpToMaximumAttempts() {
        for (String timeoutType : List.of("connect timeout", "read timeout")) {
            Harness harness = harness(3, Duration.ofSeconds(3));
            harness.server().expect(times(3), requestTo("https://api.github.com/repos/octo/demo"))
                    .andRespond(request -> {
                        throw new ResourceAccessException(timeoutType + " " + TOKEN);
                    });

            assertThatThrownBy(() -> get(harness))
                    .isInstanceOfSatisfying(GitHubApiException.class, exception -> {
                        assertThat(exception.failureType()).isEqualTo(GitHubApiFailureType.NETWORK_ERROR);
                        assertThat(exception.retryable()).isTrue();
                        assertThat(exception.getMessage()).doesNotContain(TOKEN, timeoutType);
                    });
            assertThat(harness.delays()).hasSize(2);
            harness.server().verify();
        }
    }

    @Test
    void transientServerFailureRetriesThenSucceedsAndRecordsMetrics() {
        Harness harness = harness(3, Duration.ofSeconds(3));
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andRespond(withStatus(HttpStatus.SERVICE_UNAVAILABLE));
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andRespond(withSuccess("{\"id\":321}", MediaType.APPLICATION_JSON));

        GitHubApiResponse<IdResponse> response = get(harness);

        assertThat(response.body().id()).isEqualTo(321L);
        assertThat(harness.delays()).hasSize(1);
        assertThat(harness.registry().counter(
                "devpilot.github.api.retries",
                "operation", "repository.metadata.get",
                "failure_type", "TRANSIENT_SERVER_ERROR"
        ).count()).isEqualTo(1.0);
        harness.server().verify();
    }

    @Test
    void longRateLimitWaitReturnsImmediatelyWithoutCallingSleeper() {
        Harness harness = harness(3, Duration.ofSeconds(3));
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andRespond(withStatus(HttpStatus.TOO_MANY_REQUESTS)
                        .header(HttpHeaders.RETRY_AFTER, "30")
                        .header("X-GitHub-Request-Id", "rate-request"));

        assertThatThrownBy(() -> get(harness))
                .isInstanceOfSatisfying(GitHubApiException.class, exception -> {
                    assertThat(exception.failureType()).isEqualTo(GitHubApiFailureType.RATE_LIMITED);
                    assertThat(exception.retryAt()).isEqualTo(NOW.plusSeconds(30));
                });
        assertThat(harness.delays()).isEmpty();
        harness.server().verify();
    }

    @Test
    void sendsConditionalHeadersAndTreats304AsSuccess() {
        Harness harness = harness(1, Duration.ofSeconds(3));
        harness.server().expect(requestTo("https://api.github.com/repos/octo/demo"))
                .andExpect(header(HttpHeaders.IF_NONE_MATCH, "\"etag-v1\""))
                .andExpect(header(HttpHeaders.IF_MODIFIED_SINCE, "Fri, 1 Aug 2025 00:00:00 GMT"))
                .andRespond(withStatus(HttpStatus.NOT_MODIFIED)
                        .header("X-GitHub-Request-Id", "conditional-request"));

        GitHubApiResponse<IdResponse> response = harness.executor().get(
                "repository.metadata.get",
                "/repos/{owner}/{repo}",
                List.of("repos", "octo", "demo"),
                CREDENTIAL,
                new GitHubConditionalRequest(
                        "\"etag-v1\"", Instant.parse("2025-08-01T00:00:00Z")
                ),
                IdResponse.class
        );

        assertThat(response.notModified()).isTrue();
        assertThat(response.body()).isNull();
        assertThat(response.rateLimit().requestId()).isEqualTo("conditional-request");
        harness.server().verify();
    }

    @Test
    void encodesPathSegmentsAndRejectsOffHostRedirect() {
        Harness harness = harness(1, Duration.ofSeconds(3));
        harness.server().expect(requestTo("https://api.github.com/repos/octo/repo%20name"))
                .andRespond(withStatus(HttpStatus.MOVED_PERMANENTLY)
                        .location(URI.create("https://evil.example/repositories/123")));

        assertThatThrownBy(() -> harness.executor().get(
                "repository.metadata.get",
                "/repos/{owner}/{repo}",
                List.of("repos", "octo", "repo name"),
                CREDENTIAL,
                GitHubConditionalRequest.none(),
                IdResponse.class
        )).isInstanceOfSatisfying(GitHubApiException.class, exception ->
                assertThat(exception.failureType()).isEqualTo(GitHubApiFailureType.VALIDATION));
        harness.server().verify();
    }

    @Test
    void commitClientUsesSincePerPageAndOpaqueLinkForMultiplePages() {
        Harness harness = harness(1, Duration.ofSeconds(3));
        harness.server().expect(request -> {
                    assertThat(request.getURI().getPath()).isEqualTo("/repos/octo/demo/commits");
                    assertThat(request.getURI().getQuery())
                            .contains("since=2026-07-25T00:00:00Z", "per_page=100");
                })
                .andRespond(withSuccess("[" + commitJson("a", "2026-08-01T10:00:00Z") + "]",
                        MediaType.APPLICATION_JSON)
                        .header(HttpHeaders.LINK,
                                "<https://api.github.com/repositories/123/commits?after=opaque>; rel=\"next\""));
        harness.server().expect(requestTo(
                        "https://api.github.com/repositories/123/commits?after=opaque"
                ))
                .andRespond(withSuccess("[" + commitJson("b", "2026-08-01T11:00:00Z") + "]",
                        MediaType.APPLICATION_JSON));
        RestClientGitHubCommitClient client = new RestClientGitHubCommitClient(harness.executor());

        GitHubPage<GitHubCommit> first = client.listCommits(
                "octo", "demo", Instant.parse("2026-07-25T00:00:00Z"),
                100, CREDENTIAL, GitHubPageCursor.empty()
        );
        GitHubPage<GitHubCommit> second = client.listCommits(
                "octo", "demo", Instant.EPOCH, 1, CREDENTIAL, first.cursor()
        );

        assertThat(first.items()).extracting(GitHubCommit::sha).containsExactly("a".repeat(40));
        assertThat(second.items()).extracting(GitHubCommit::sha).containsExactly("b".repeat(40));
        harness.server().verify();
    }

    @Test
    void commitClientHandlesEmptyJsonArrayAndRejectsMissingSha() {
        Harness emptyHarness = harness(1, Duration.ofSeconds(3));
        emptyHarness.server().expect(request -> { })
                .andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));
        RestClientGitHubCommitClient emptyClient =
                new RestClientGitHubCommitClient(emptyHarness.executor());
        assertThat(emptyClient.listCommits(
                "octo", "demo", NOW, 100, CREDENTIAL, null
        ).items()).isEmpty();
        emptyHarness.server().verify();

        Harness malformedHarness = harness(1, Duration.ofSeconds(3));
        malformedHarness.server().expect(request -> { })
                .andRespond(withSuccess("[{\"commit\":{}}]", MediaType.APPLICATION_JSON));
        RestClientGitHubCommitClient malformedClient =
                new RestClientGitHubCommitClient(malformedHarness.executor());
        assertThatThrownBy(() -> malformedClient.listCommits(
                "octo", "demo", NOW, 100, CREDENTIAL, null
        )).isInstanceOfSatisfying(GitHubApiException.class, exception ->
                assertThat(exception.failureType()).isEqualTo(GitHubApiFailureType.MALFORMED_RESPONSE));
        malformedHarness.server().verify();
    }

    private GitHubApiResponse<IdResponse> get(Harness harness) {
        return harness.executor().get(
                "repository.metadata.get",
                "/repos/{owner}/{repo}",
                List.of("repos", "octo", "demo"),
                CREDENTIAL,
                GitHubConditionalRequest.none(),
                IdResponse.class
        );
    }

    private Harness harness(int maxAttempts, Duration maxRateLimitWait) {
        RestClient.Builder builder = RestClient.builder()
                .baseUrl("https://api.github.com")
                .defaultHeader(HttpHeaders.ACCEPT, "application/vnd.github+json")
                .defaultHeader("X-GitHub-Api-Version", "2022-11-28")
                .defaultHeader(HttpHeaders.USER_AGENT, "DevPilot");
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        GitHubApiEndpointPolicy endpointPolicy = new GitHubApiEndpointPolicy(
                URI.create("https://api.github.com"), false
        );
        GitHubRateLimitParser rateLimitParser = new GitHubRateLimitParser(clock);
        GitHubApiErrorDecoder decoder = new GitHubApiErrorDecoder(
                new ObjectMapper(), rateLimitParser, clock
        );
        List<Duration> delays = new ArrayList<>();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        GitHubAccessTokenProvider tokenProvider = reference ->
                CREDENTIAL.equals(reference)
                        ? Optional.of(new GitHubAccessToken(TOKEN, null))
                        : Optional.empty();
        GitHubApiHttpExecutor executor = new GitHubApiHttpExecutor(
                builder.build(),
                new ObjectMapper(),
                tokenProvider,
                new GitHubCredentialConcurrencyLimiter(2, Duration.ofMillis(50)),
                new GitHubApiRetryPolicy(
                        GitHubTestProperties.api(
                                maxAttempts,
                                Duration.ofMillis(200),
                                Duration.ofSeconds(2),
                                maxRateLimitWait,
                                2,
                                Duration.ofMillis(50)
                        ),
                        clock,
                        () -> 0.5
                ),
                duration -> delays.add(duration),
                decoder,
                rateLimitParser,
                new GitHubLinkHeaderParser(endpointPolicy),
                endpointPolicy,
                new GitHubApiMetrics(registry)
        );
        return new Harness(executor, server, delays, registry);
    }

    private static Stream<Arguments> httpFailures() {
        return Stream.of(
                Arguments.of(HttpStatus.BAD_REQUEST, GitHubApiFailureType.VALIDATION),
                Arguments.of(HttpStatus.UNAUTHORIZED, GitHubApiFailureType.AUTHENTICATION),
                Arguments.of(HttpStatus.FORBIDDEN, GitHubApiFailureType.ACCESS_DENIED),
                Arguments.of(HttpStatus.NOT_FOUND, GitHubApiFailureType.NOT_FOUND),
                Arguments.of(HttpStatus.UNPROCESSABLE_ENTITY, GitHubApiFailureType.VALIDATION),
                Arguments.of(HttpStatus.TOO_MANY_REQUESTS, GitHubApiFailureType.RATE_LIMITED),
                Arguments.of(HttpStatus.INTERNAL_SERVER_ERROR, GitHubApiFailureType.TRANSIENT_SERVER_ERROR),
                Arguments.of(HttpStatus.BAD_GATEWAY, GitHubApiFailureType.TRANSIENT_SERVER_ERROR),
                Arguments.of(HttpStatus.SERVICE_UNAVAILABLE, GitHubApiFailureType.TRANSIENT_SERVER_ERROR),
                Arguments.of(HttpStatus.GATEWAY_TIMEOUT, GitHubApiFailureType.TRANSIENT_SERVER_ERROR)
        );
    }

    private String commitJson(String shaCharacter, String committedAt) {
        return """
                {
                  "sha":"%s",
                  "commit":{
                    "message":"message",
                    "author":{"name":"Octo","email":"private@example.com","date":"%s"},
                    "committer":{"name":"Octo","email":"private@example.com","date":"%s"}
                  },
                  "author":{"id":7,"login":"octocat"},
                  "html_url":"https://github.com/octo/demo/commit/%s"
                }
                """.formatted(
                shaCharacter.repeat(40), committedAt, committedAt, shaCharacter.repeat(40)
        );
    }

    private record IdResponse(long id) {
    }

    private record Harness(
            GitHubApiHttpExecutor executor,
            MockRestServiceServer server,
            List<Duration> delays,
            SimpleMeterRegistry registry
    ) {
    }
}
