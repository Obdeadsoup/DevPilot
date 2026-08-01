package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.http.HttpHeaders;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubApiErrorDecoderTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private final Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
    private final GitHubApiErrorDecoder decoder = new GitHubApiErrorDecoder(
            new ObjectMapper(), new GitHubRateLimitParser(clock), clock
    );

    @ParameterizedTest
    @MethodSource("ordinaryStatuses")
    void classifiesOrdinaryHttpStatuses(
            int status,
            GitHubApiFailureType failureType,
            boolean retryable
    ) {
        GitHubApiException failure = decoder.decode(status, new HttpHeaders(), new byte[0]);

        assertThat(failure.failureType()).isEqualTo(failureType);
        assertThat(failure.retryable()).isEqualTo(retryable);
        assertThat(failure.httpStatus()).isEqualTo(status);
    }

    @Test
    void distinguishesOrdinary403FromPrimaryAndSecondaryRateLimit() {
        GitHubApiException ordinary = decoder.decode(403, new HttpHeaders(), json("forbidden"));

        HttpHeaders primaryHeaders = new HttpHeaders();
        primaryHeaders.set("X-RateLimit-Remaining", "0");
        primaryHeaders.set("X-RateLimit-Reset", Long.toString(NOW.plusSeconds(2).getEpochSecond()));
        GitHubApiException primary = decoder.decode(403, primaryHeaders, json("API rate limit exceeded"));

        HttpHeaders secondaryHeaders = new HttpHeaders();
        secondaryHeaders.set(HttpHeaders.RETRY_AFTER, "1");
        GitHubApiException secondary = decoder.decode(
                403, secondaryHeaders, json("You have exceeded a secondary rate limit")
        );

        assertThat(ordinary.failureType()).isEqualTo(GitHubApiFailureType.ACCESS_DENIED);
        assertThat(ordinary.retryable()).isFalse();
        assertThat(primary.failureType()).isEqualTo(GitHubApiFailureType.RATE_LIMITED);
        assertThat(primary.retryAt()).isEqualTo(NOW.plusSeconds(2));
        assertThat(secondary.failureType()).isEqualTo(GitHubApiFailureType.RATE_LIMITED);
        assertThat(secondary.retryAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void parsesRateLimitHeadersAndRequestIdWithoutLeakingBody() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-RateLimit-Limit", "5000");
        headers.set("X-RateLimit-Remaining", "0");
        headers.set("X-RateLimit-Used", "5000");
        headers.set("X-RateLimit-Reset", Long.toString(NOW.plusSeconds(10).getEpochSecond()));
        headers.set("X-RateLimit-Resource", "core");
        headers.set(HttpHeaders.RETRY_AFTER, "3");
        headers.set("X-GitHub-Request-Id", "request-123");

        GitHubApiException failure = decoder.decode(
                429, headers, json("private upstream token secret-token")
        );

        assertThat(failure.rateLimit()).isEqualTo(new GitHubRateLimitSnapshot(
                5_000L,
                0L,
                5_000L,
                NOW.plusSeconds(10),
                "core",
                java.time.Duration.ofSeconds(3),
                "request-123"
        ));
        assertThat(failure.requestId()).isEqualTo("request-123");
        assertThat(failure.getMessage()).doesNotContain("private upstream", "secret-token");
    }

    private byte[] json(String message) {
        return ("{\"message\":\"" + message + "\"}").getBytes(StandardCharsets.UTF_8);
    }

    private static Stream<Arguments> ordinaryStatuses() {
        return Stream.of(
                Arguments.of(400, GitHubApiFailureType.VALIDATION, false),
                Arguments.of(401, GitHubApiFailureType.AUTHENTICATION, false),
                Arguments.of(403, GitHubApiFailureType.ACCESS_DENIED, false),
                Arguments.of(404, GitHubApiFailureType.NOT_FOUND, false),
                Arguments.of(409, GitHubApiFailureType.CONFLICT, false),
                Arguments.of(422, GitHubApiFailureType.VALIDATION, false),
                Arguments.of(429, GitHubApiFailureType.RATE_LIMITED, true),
                Arguments.of(500, GitHubApiFailureType.TRANSIENT_SERVER_ERROR, true),
                Arguments.of(502, GitHubApiFailureType.TRANSIENT_SERVER_ERROR, true),
                Arguments.of(503, GitHubApiFailureType.TRANSIENT_SERVER_ERROR, true),
                Arguments.of(504, GitHubApiFailureType.TRANSIENT_SERVER_ERROR, true)
        );
    }
}
