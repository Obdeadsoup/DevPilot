package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.client.GitHubApiResponse;
import com.obdeadsoup.devpilot.github.application.client.GitHubCommit;
import com.obdeadsoup.devpilot.github.application.client.GitHubCommitClient;
import com.obdeadsoup.devpilot.github.application.client.GitHubPage;
import com.obdeadsoup.devpilot.github.application.client.GitHubPageCursor;
import com.obdeadsoup.devpilot.github.application.client.GitHubRateLimitSnapshot;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.application.client.VerifiedGitHubRepository;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncCheckpointEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncTarget;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import com.obdeadsoup.devpilot.github.support.GitHubTestProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubCommitReconciliationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-01T12:00:00Z");

    private final GitHubSyncRunStateService runStateService = mock(GitHubSyncRunStateService.class);
    private final GitHubRepositoryMapper repositoryMapper = mock(GitHubRepositoryMapper.class);
    private final GitHubRepositoryMetadataClient metadataClient = mock(GitHubRepositoryMetadataClient.class);
    private final GitHubCommitClient commitClient = mock(GitHubCommitClient.class);
    private final GitHubSyncCheckpointService checkpointService = mock(GitHubSyncCheckpointService.class);
    private final GitHubCommitApplicationService commitService = mock(GitHubCommitApplicationService.class);
    private final GitHubSyncFailureClassifier classifier = mock(GitHubSyncFailureClassifier.class);
    private final GitHubCommitReconciliationService service = new GitHubCommitReconciliationService(
            runStateService, repositoryMapper, metadataClient, commitClient, checkpointService,
            commitService, classifier, GitHubTestProperties.reconciliation(),
            Clock.fixed(NOW, ZoneOffset.UTC)
    );

    private GitHubSyncRunEntity run;
    private GitHubSyncCheckpointEntity checkpoint;

    @BeforeEach
    void setUp() {
        run = new GitHubSyncRunEntity(
                1, 10, "COMMIT", "SCHEDULED", "RUNNING", 1,
                null, LocalDateTime.ofInstant(NOW, ZoneOffset.UTC), null, null, null, null,
                LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 0), 1
        );
        checkpoint = new GitHubSyncCheckpointEntity(
                2, 10, "COMMIT", LocalDateTime.of(2026, 8, 1, 10, 0), null, 300,
                LocalDateTime.of(2026, 8, 1, 0, 0), LocalDateTime.of(2026, 8, 1, 0, 0), 0
        );
        when(runStateService.claim(1)).thenReturn(Optional.of(run));
        when(repositoryMapper.findSyncTarget(10)).thenReturn(Optional.of(target()));
        when(checkpointService.getOrCreate(10)).thenReturn(checkpoint);
        when(checkpointService.calculateSince(checkpoint, NOW))
                .thenReturn(Instant.parse("2026-08-01T09:55:00Z"));
        when(metadataClient.getRepository(any(), any(), any(), any())).thenReturn(metadata());
    }

    @Test
    void pageFailureDoesNotAdvanceCheckpointPastUnpersistedCommit() {
        GitHubCommit first = commit("a", "2026-08-01T10:10:00Z");
        GitHubCommit second = commit("b", "2026-08-01T10:20:00Z");
        when(commitClient.listCommits(any(), any(), any(), any(Integer.class), any(), any()))
                .thenReturn(new GitHubPage<>(List.of(first, second), GitHubPageCursor.empty()));
        AtomicInteger calls = new AtomicInteger();
        doAnswer(invocation -> {
            if (calls.incrementAndGet() == 2) {
                throw new IllegalStateException("simulated persistence failure");
            }
            return new GitHubCommitApplicationService.UpsertResult(1, true);
        }).when(commitService).upsert(any());
        GitHubSyncFailureClassifier.Classification failure =
                new GitHubSyncFailureClassifier.Classification("SYNC_PROCESSING_ERROR", "safe", true, null);
        when(classifier.classify(any())).thenReturn(failure);

        service.reconcile(1);

        verify(checkpointService, never()).recordPage(any(), any());
        verify(runStateService).handleFailure(run, failure);
        verify(runStateService, never()).complete(any(), any(), any(), any());
    }

    @Test
    void successfulPageAdvancesProgressThenCompletesAtNewestGitHubTimestamp() {
        GitHubCommit commit = commit("c", "2026-08-01T10:30:00Z");
        when(commitClient.listCommits(any(), any(), any(), any(Integer.class), any(), any()))
                .thenReturn(new GitHubPage<>(List.of(commit), GitHubPageCursor.empty()));
        GitHubSyncCheckpointEntity progressed = new GitHubSyncCheckpointEntity(
                2, 10, "COMMIT", checkpoint.lastSuccessfulSyncAt(), commit.sha(), 300,
                checkpoint.createdAt(), checkpoint.updatedAt(), 1
        );
        when(checkpointService.recordPage(checkpoint, commit.sha())).thenReturn(progressed);

        service.reconcile(1);

        verify(commitService).upsert(any());
        verify(checkpointService).recordPage(checkpoint, commit.sha());
        verify(runStateService).complete(
                run,
                progressed,
                LocalDateTime.of(2026, 8, 1, 10, 30),
                commit.sha()
        );
    }

    private GitHubSyncTarget target() {
        return new GitHubSyncTarget(
                10, 100, 200, 123456, "octo", "demo", "octo/demo", "TOKEN_REF",
                "ACTIVE", false, "ACTIVE", false, "ACTIVE", false
        );
    }

    private GitHubApiResponse<VerifiedGitHubRepository> metadata() {
        return new GitHubApiResponse<>(
                200,
                new VerifiedGitHubRepository(
                        123456, "octo", "demo", "octo/demo",
                        "https://github.com/octo/demo", "main", "private"
                ),
                false, null, null,
                new GitHubRateLimitSnapshot(5000L, 4999L, 1L, null, "core", null, "request"),
                GitHubPageCursor.empty()
        );
    }

    private GitHubCommit commit(String character, String committedAt) {
        return new GitHubCommit(
                character.repeat(40), "message", "Author", "private@example.com",
                7L, "octocat", Instant.parse(committedAt), Instant.parse(committedAt),
                "https://github.com/octo/demo/commit/" + character.repeat(40)
        );
    }
}
