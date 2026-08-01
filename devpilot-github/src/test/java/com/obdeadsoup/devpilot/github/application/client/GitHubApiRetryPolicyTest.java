package com.obdeadsoup.devpilot.github.application.client;

import com.obdeadsoup.devpilot.github.support.GitHubTestProperties;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubApiRetryPolicyTest {

    private static final Instant NOW = Instant.parse("2026-08-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);

    @Test
    void retryAfterHasPriorityOverResetAndBackoff() {
        GitHubApiRetryPolicy policy = policy(3, Duration.ofMillis(200), Duration.ofSeconds(2),
                Duration.ofSeconds(3), 0.5);
        GitHubRateLimitSnapshot rateLimit = new GitHubRateLimitSnapshot(
                5_000L, 0L, 5_000L, NOW.plusSeconds(2), "core",
                Duration.ofSeconds(1), "request-id"
        );

        GitHubApiRetryPolicy.Decision decision = policy.decide(
                HttpMethod.GET, 1, failure(GitHubApiFailureType.RATE_LIMITED, rateLimit)
        );

        assertThat(decision.retry()).isTrue();
        assertThat(decision.delay()).isEqualTo(Duration.ofSeconds(1));
        assertThat(decision.retryAt()).isEqualTo(NOW.plusSeconds(1));
    }

    @Test
    void resetHasPriorityWhenRetryAfterIsMissing() {
        GitHubApiRetryPolicy policy = policy(3, Duration.ofMillis(200), Duration.ofSeconds(2),
                Duration.ofSeconds(3), 0.5);
        GitHubRateLimitSnapshot rateLimit = new GitHubRateLimitSnapshot(
                5_000L, 0L, 5_000L, NOW.plusSeconds(2), "core", null, "request-id"
        );

        GitHubApiRetryPolicy.Decision decision = policy.decide(
                HttpMethod.HEAD, 1, failure(GitHubApiFailureType.RATE_LIMITED, rateLimit)
        );

        assertThat(decision.retry()).isTrue();
        assertThat(decision.delay()).isEqualTo(Duration.ofSeconds(2));
    }

    @Test
    void exponentialBackoffUsesBoundedJitterAndStopsAtAttemptLimit() {
        GitHubApiRetryPolicy low = policy(3, Duration.ofMillis(200), Duration.ofSeconds(2),
                Duration.ofSeconds(3), 0.0);
        GitHubApiRetryPolicy high = policy(3, Duration.ofMillis(200), Duration.ofSeconds(2),
                Duration.ofSeconds(3), Math.nextDown(1.0));
        GitHubApiException network = failure(GitHubApiFailureType.NETWORK_ERROR, null);

        assertThat(low.decide(HttpMethod.GET, 2, network).delay())
                .isEqualTo(Duration.ofMillis(200));
        assertThat(high.decide(HttpMethod.GET, 2, network).delay())
                .isBetween(Duration.ofMillis(599), Duration.ofMillis(600));
        assertThat(low.decide(HttpMethod.GET, 3, network).retry()).isFalse();
    }

    @Test
    void longRateLimitWaitAndWriteMethodsDoNotRetrySynchronously() {
        GitHubApiRetryPolicy policy = policy(3, Duration.ofMillis(200), Duration.ofSeconds(2),
                Duration.ofSeconds(3), 0.5);
        GitHubRateLimitSnapshot longWait = new GitHubRateLimitSnapshot(
                null, 0L, null, NOW.plusSeconds(30), "core", null, "request-id"
        );

        assertThat(policy.decide(
                HttpMethod.GET, 1, failure(GitHubApiFailureType.RATE_LIMITED, longWait)
        ).retry()).isFalse();
        assertThat(policy.decide(
                HttpMethod.POST, 1, failure(GitHubApiFailureType.NETWORK_ERROR, null)
        ).retry()).isFalse();
        assertThat(policy.decide(
                HttpMethod.GET, 1, failure(GitHubApiFailureType.MALFORMED_RESPONSE, null)
        ).retry()).isFalse();
    }

    private GitHubApiRetryPolicy policy(
            int attempts,
            Duration initial,
            Duration max,
            Duration maxRateLimitWait,
            double jitter
    ) {
        return new GitHubApiRetryPolicy(
                GitHubTestProperties.api(
                        attempts, initial, max, maxRateLimitWait,
                        2, Duration.ofMillis(100)
                ),
                CLOCK,
                () -> jitter
        );
    }

    private GitHubApiException failure(
            GitHubApiFailureType type,
            GitHubRateLimitSnapshot rateLimit
    ) {
        return new GitHubApiException(
                type,
                type == GitHubApiFailureType.NETWORK_ERROR
                        || type == GitHubApiFailureType.TRANSIENT_SERVER_ERROR
                        || type == GitHubApiFailureType.RATE_LIMITED,
                null,
                null,
                "safe failure",
                rateLimit == null ? null : rateLimit.requestId(),
                rateLimit
        );
    }
}
