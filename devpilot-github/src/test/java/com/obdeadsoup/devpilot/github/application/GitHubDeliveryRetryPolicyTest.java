package com.obdeadsoup.devpilot.github.application;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubDeliveryRetryPolicyTest {

    @Test
    void usesInitialDelayForFirstFailure() {
        GitHubDeliveryRetryPolicy policy = policy(3);

        assertThat(policy.retryDelay(1)).isEqualTo(Duration.ofSeconds(10));
    }

    @Test
    void doublesDelayForSubsequentFailures() {
        GitHubDeliveryRetryPolicy policy = policy(3);

        assertThat(policy.retryDelay(2)).isEqualTo(Duration.ofSeconds(20));
        assertThat(policy.retryDelay(3)).isEqualTo(Duration.ofSeconds(40));
    }

    @Test
    void capsDelayAtMaximum() {
        GitHubDeliveryRetryPolicy policy = policy(20);

        assertThat(policy.retryDelay(6)).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.retryDelay(10)).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void maxRetriesZeroMakesFirstFailureTerminal() {
        GitHubDeliveryRetryPolicy policy = policy(0);

        assertThat(policy.shouldRetryAfterFailure(0)).isFalse();
    }

    @Test
    void stopsSchedulingAtRetryLimit() {
        GitHubDeliveryRetryPolicy policy = policy(3);

        assertThat(policy.shouldRetryAfterFailure(2)).isTrue();
        assertThat(policy.shouldRetryAfterFailure(3)).isFalse();
    }

    @Test
    void extremeRetryCountDoesNotOverflow() {
        GitHubDeliveryRetryPolicy policy = policy(20);

        assertThat(policy.retryDelay(Integer.MAX_VALUE)).isEqualTo(Duration.ofMinutes(5));
    }

    private GitHubDeliveryRetryPolicy policy(int maxRetries) {
        return new GitHubDeliveryRetryPolicy(
                maxRetries,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5)
        );
    }
}
