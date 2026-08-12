package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.GitHubSyncFailureClassifier.Classification;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncRunStatus;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncRunMapper;
import com.obdeadsoup.devpilot.github.support.GitHubTestProperties;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class GitHubSyncRunStateServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    private final GitHubSyncRunMapper mapper = mock(GitHubSyncRunMapper.class);
    private final GitHubSyncCheckpointService checkpointService = mock(GitHubSyncCheckpointService.class);
    private final GitHubSyncRetryPolicy retryPolicy =
            new GitHubSyncRetryPolicy(GitHubTestProperties.reconciliation());
    private final GitHubSyncRunStateService service = new GitHubSyncRunStateService(
            mapper, checkpointService, retryPolicy, Clock.fixed(NOW, ZoneOffset.UTC),
            new GitHubSyncMetrics(new SimpleMeterRegistry())
    );

    @Test
    void duplicateWorkersCanOnlyClaimOnce() {
        GitHubSyncRunEntity pending = run("PENDING", 0, 0);
        GitHubSyncRunEntity running = run("RUNNING", 1, 1);
        when(mapper.findById(1)).thenReturn(Optional.of(pending), Optional.of(running), Optional.of(pending));
        when(mapper.claim(eq(1L), eq(0L), any(LocalDateTime.class))).thenReturn(1, 0);

        assertThat(service.claim(1)).contains(running);
        assertThat(service.claim(1)).isEmpty();
    }

    @Test
    void futureRateLimitMovesRunToRetryWait() {
        GitHubSyncRunEntity running = run("RUNNING", 1, 1);
        Instant retryAt = NOW.plusSeconds(600);
        when(mapper.markRetryWait(
                eq(1L), eq(1L), any(LocalDateTime.class), eq("GITHUB_API_RATE_LIMITED"), any()
        )).thenReturn(1);

        assertThat(service.handleFailure(running, new Classification(
                "GITHUB_API_RATE_LIMITED", "rate limited", true, retryAt
        ))).contains(GitHubSyncRunStatus.RETRY_WAIT);
        verify(mapper).markRetryWait(
                1, 1, LocalDateTime.ofInstant(retryAt, ZoneOffset.UTC),
                "GITHUB_API_RATE_LIMITED", "rate limited"
        );
    }

    @Test
    void permanentErrorMovesRunDirectlyToDead() {
        GitHubSyncRunEntity running = run("RUNNING", 1, 1);
        when(mapper.markDead(eq(1L), eq(1L), any(), eq("GITHUB_API_AUTHENTICATION"), any()))
                .thenReturn(1);

        assertThat(service.handleFailure(running, new Classification(
                "GITHUB_API_AUTHENTICATION", "authentication failed", false, null
        ))).contains(GitHubSyncRunStatus.DEAD);
    }

    @Test
    void staleRunningRunIsRecoveredWithVersionAndCutoff() {
        GitHubSyncRunEntity running = run("RUNNING", 1, 1);
        LocalDateTime cutoff = LocalDateTime.of(2026, 8, 1, 11, 45);
        when(mapper.recoverStaleToRetryWait(
                eq(1L), eq(1L), eq(cutoff), any(LocalDateTime.class)
        )).thenReturn(1);

        assertThat(service.recoverStale(running, cutoff))
                .contains(GitHubSyncRunStatus.RETRY_WAIT);
    }

    private GitHubSyncRunEntity run(String status, int attemptCount, long version) {
        return new GitHubSyncRunEntity(
                1, 10, "COMMIT", "MANUAL", status, attemptCount,
                null, null, null, null, null, 7L,
                LocalDateTime.of(2026, 8, 1, 0, 0),
                LocalDateTime.of(2026, 8, 1, 0, 0), version
        );
    }
}
