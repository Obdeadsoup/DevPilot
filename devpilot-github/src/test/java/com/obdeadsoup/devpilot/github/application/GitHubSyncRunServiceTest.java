package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.domain.GitHubSyncTriggerType;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncCheckpointMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncRunMapper;
import com.obdeadsoup.devpilot.github.support.GitHubTestProperties;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GitHubSyncRunServiceTest {

    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ProjectAuthorizationService authorizationService = mock(ProjectAuthorizationService.class);
    private final GitHubRepositoryMapper repositoryMapper = mock(GitHubRepositoryMapper.class);
    private final GitHubSyncCheckpointMapper checkpointMapper = mock(GitHubSyncCheckpointMapper.class);
    private final GitHubSyncRunMapper runMapper = mock(GitHubSyncRunMapper.class);
    private final GitHubSyncRunStateService stateService = mock(GitHubSyncRunStateService.class);
    private final GitHubCommitReconciliationService reconciliationService =
            mock(GitHubCommitReconciliationService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-08-01T12:00:00Z"), ZoneOffset.UTC);

    @Test
    void repeatedSchedulerDiscoveryMayResubmitButLeavesFinalClaimToWorker() {
        GitHubSyncRunEntity pending = pendingRun();
        when(repositoryMapper.findSyncEligibleBindingIds(25)).thenReturn(List.of(10L));
        when(checkpointMapper.findCommitCheckpoint(10)).thenReturn(Optional.empty());
        when(stateService.createOrGetOpen(10, GitHubSyncTriggerType.INITIAL, null))
                .thenReturn(new GitHubSyncRunStateService.CreationResult(pending, false));
        when(runMapper.findRunnableCandidateIds(LocalDateTime.of(2026, 8, 1, 12, 0), 25))
                .thenReturn(List.of(1L));
        GitHubSyncRunService service = service(Runnable::run);

        service.discoverAndSubmit();
        service.discoverAndSubmit();

        verify(stateService, times(2)).createOrGetOpen(10, GitHubSyncTriggerType.INITIAL, null);
        verify(reconciliationService, times(2)).reconcile(1);
    }

    @Test
    void executorRejectionDoesNotInvokeWorkerOrClaimRun() {
        when(repositoryMapper.findSyncEligibleBindingIds(25)).thenReturn(List.of());
        when(runMapper.findRunnableCandidateIds(LocalDateTime.of(2026, 8, 1, 12, 0), 25))
                .thenReturn(List.of(1L));
        TaskExecutor rejected = task -> {
            throw new TaskRejectedException("full");
        };

        service(rejected).discoverAndSubmit();

        verify(reconciliationService, never()).reconcile(1);
    }

    private GitHubSyncRunService service(TaskExecutor executor) {
        return new GitHubSyncRunService(
                currentUserProvider, authorizationService, repositoryMapper, checkpointMapper,
                runMapper, stateService, reconciliationService, executor,
                GitHubTestProperties.reconciliation(), clock
        );
    }

    private GitHubSyncRunEntity pendingRun() {
        return new GitHubSyncRunEntity(
                1, 10, "COMMIT", "INITIAL", "PENDING", 0,
                null, null, null, null, null, null,
                LocalDateTime.of(2026, 8, 1, 12, 0),
                LocalDateTime.of(2026, 8, 1, 12, 0), 0
        );
    }
}
