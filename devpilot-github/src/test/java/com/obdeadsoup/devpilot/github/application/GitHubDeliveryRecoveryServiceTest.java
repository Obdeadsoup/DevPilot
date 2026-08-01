package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.config.GitHubIntegrationProperties;
import com.obdeadsoup.devpilot.github.support.GitHubTestProperties;
import com.obdeadsoup.devpilot.github.domain.GitHubDeliveryStatus;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class GitHubDeliveryRecoveryServiceTest {

    private static final Instant NOW = Instant.parse("2026-07-28T12:00:00Z");
    private static final LocalDateTime CUTOFF = LocalDateTime.of(2026, 7, 28, 11, 58);

    @Test
    void recoversStaleProcessingBeforeSubmittingDistinctReceivedAndDueCandidates() {
        GitHubDeliveryMapper mapper = mock(GitHubDeliveryMapper.class);
        GitHubDeliveryStateService stateService = mock(GitHubDeliveryStateService.class);
        GitHubDeliveryWorker worker = mock(GitHubDeliveryWorker.class);
        GitHubDeliveryEntity stale = delivery(10, "PROCESSING", 0, 1);
        List<Runnable> submitted = new ArrayList<>();
        TaskExecutor executor = submitted::add;

        when(mapper.findStaleProcessingCandidates(CUTOFF, 50)).thenReturn(List.of(stale));
        when(stateService.recoverStaleProcessing(stale, CUTOFF))
                .thenReturn(Optional.of(GitHubDeliveryStatus.RETRY_WAIT));
        when(mapper.findReceivedCandidateIds(50)).thenReturn(List.of(11L, 12L));
        when(mapper.findDueRetryCandidateIds(LocalDateTime.of(2026, 7, 28, 12, 0), 50))
                .thenReturn(List.of(12L, 13L));

        recoveryService(mapper, stateService, worker, executor).recover();

        InOrder order = inOrder(mapper, stateService);
        order.verify(mapper).findStaleProcessingCandidates(CUTOFF, 50);
        order.verify(stateService).recoverStaleProcessing(stale, CUTOFF);
        order.verify(mapper).findReceivedCandidateIds(50);
        order.verify(mapper).findDueRetryCandidateIds(LocalDateTime.of(2026, 7, 28, 12, 0), 50);
        assertThat(submitted).hasSize(3);
        verifyNoInteractions(worker);

        submitted.forEach(Runnable::run);
        verify(worker).process(11L);
        verify(worker).process(12L);
        verify(worker).process(13L);
    }

    @Test
    void rejectedSubmissionLeavesCandidateUnclaimedForNextScan() {
        GitHubDeliveryMapper mapper = mock(GitHubDeliveryMapper.class);
        GitHubDeliveryStateService stateService = mock(GitHubDeliveryStateService.class);
        GitHubDeliveryWorker worker = mock(GitHubDeliveryWorker.class);
        TaskExecutor rejectingExecutor = task -> {
            throw new TaskRejectedException("queue full");
        };

        when(mapper.findStaleProcessingCandidates(CUTOFF, 50)).thenReturn(List.of());
        when(mapper.findReceivedCandidateIds(50)).thenReturn(List.of(42L));
        when(mapper.findDueRetryCandidateIds(LocalDateTime.of(2026, 7, 28, 12, 0), 50))
                .thenReturn(List.of());

        recoveryService(mapper, stateService, worker, rejectingExecutor).recover();

        verify(worker, never()).process(42L);
        verifyNoInteractions(stateService);
    }

    private GitHubDeliveryRecoveryService recoveryService(
            GitHubDeliveryMapper mapper,
            GitHubDeliveryStateService stateService,
            GitHubDeliveryWorker worker,
            TaskExecutor executor
    ) {
        return new GitHubDeliveryRecoveryService(
                mapper,
                stateService,
                worker,
                executor,
                properties(),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    private GitHubIntegrationProperties properties() {
        return GitHubTestProperties.defaults();
    }

    private GitHubDeliveryEntity delivery(long id, String status, int retryCount, long version) {
        return new GitHubDeliveryEntity(
                id,
                100,
                200,
                300,
                "delivery-" + id,
                "ping",
                null,
                status,
                "{}",
                "0".repeat(64),
                retryCount,
                null,
                LocalDateTime.of(2026, 7, 28, 11, 0),
                null,
                null,
                LocalDateTime.of(2026, 7, 28, 10, 0),
                version
        );
    }
}
