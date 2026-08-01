package com.obdeadsoup.devpilot.github.application.client;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GitHubCredentialConcurrencyLimiterTest {

    @Test
    void sameCredentialIsLimitedButDifferentCredentialsDoNotBlockEachOther() throws Exception {
        GitHubCredentialConcurrencyLimiter limiter =
                new GitHubCredentialConcurrencyLimiter(1, Duration.ofMillis(50));
        ExecutorService executor = Executors.newSingleThreadExecutor();
        try (GitHubCredentialConcurrencyLimiter.Permit ignored = limiter.acquire("credential-a")) {
            Future<GitHubApiFailureType> sameCredential = executor.submit(() -> {
                try (GitHubCredentialConcurrencyLimiter.Permit permit = limiter.acquire("credential-a")) {
                    return null;
                } catch (GitHubApiException exception) {
                    return exception.failureType();
                }
            });

            try (GitHubCredentialConcurrencyLimiter.Permit other = limiter.acquire("credential-b")) {
                assertThat(other).isNotNull();
            }
            assertThat(sameCredential.get()).isEqualTo(GitHubApiFailureType.CONCURRENCY_LIMITED);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void permitIsReleasedWhenProtectedWorkThrows() {
        GitHubCredentialConcurrencyLimiter limiter =
                new GitHubCredentialConcurrencyLimiter(1, Duration.ofMillis(50));

        assertThatThrownBy(() -> {
            try (GitHubCredentialConcurrencyLimiter.Permit ignored = limiter.acquire("credential")) {
                throw new IllegalStateException("business failure");
            }
        }).isInstanceOf(IllegalStateException.class);

        try (GitHubCredentialConcurrencyLimiter.Permit acquiredAgain = limiter.acquire("credential")) {
            assertThat(acquiredAgain).isNotNull();
        }
    }
}
