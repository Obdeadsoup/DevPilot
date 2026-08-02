package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncCheckpointEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncCheckpointMapper;
import com.obdeadsoup.devpilot.github.support.GitHubTestProperties;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubSyncCheckpointServiceTest {

    private final GitHubSyncCheckpointMapper mapper = mock(GitHubSyncCheckpointMapper.class);
    private final GitHubSyncCheckpointService service = new GitHubSyncCheckpointService(
            mapper, GitHubTestProperties.reconciliation()
    );

    @Test
    void initialSyncUsesConfiguredSevenDayLookback() {
        Instant now = Instant.parse("2026-08-01T12:00:00Z");

        assertThat(service.calculateSince(checkpoint(null, 300, 0), now))
                .isEqualTo(Instant.parse("2026-07-25T12:00:00Z"));
    }

    @Test
    void subsequentSyncSubtractsCheckpointOverlapWindow() {
        LocalDateTime boundary = LocalDateTime.of(2026, 8, 1, 10, 0);

        assertThat(service.calculateSince(checkpoint(boundary, 300, 0), Instant.EPOCH))
                .isEqualTo(Instant.parse("2026-08-01T09:55:00Z"));
    }

    @Test
    void doesNotAdvancePageProgressUntilCallerReportsPersistedPage() {
        GitHubSyncCheckpointEntity before = checkpoint(null, 300, 4);

        service.calculateSince(before, Instant.parse("2026-08-01T12:00:00Z"));

        verify(mapper, never()).updatePageProgress(before.id(), before.version(), "a".repeat(40));

        GitHubSyncCheckpointEntity after = checkpoint(null, 300, 5);
        when(mapper.updatePageProgress(before.id(), before.version(), "a".repeat(40))).thenReturn(1);
        when(mapper.findCommitCheckpoint(before.repositoryBindingId())).thenReturn(Optional.of(after));

        assertThat(service.recordPage(before, "a".repeat(40)).version()).isEqualTo(5);
    }

    private GitHubSyncCheckpointEntity checkpoint(
            LocalDateTime successfulAt,
            long overlapSeconds,
            long version
    ) {
        return new GitHubSyncCheckpointEntity(
                1, 10, "COMMIT", successfulAt, null, overlapSeconds,
                LocalDateTime.of(2026, 8, 1, 0, 0),
                LocalDateTime.of(2026, 8, 1, 0, 0),
                version
        );
    }
}
