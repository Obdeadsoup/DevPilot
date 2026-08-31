package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.agent.error.AgentRunErrorCode;
import com.obdeadsoup.devpilot.agent.persistence.entity.AgentRunEntity;
import com.obdeadsoup.devpilot.agent.persistence.mapper.AgentRunMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DuplicateKeyException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunPersistenceServiceTest {
    private final AgentRunMapper mapper = mock(AgentRunMapper.class);
    private final AgentRunPersistenceService service = new AgentRunPersistenceService(mapper);
    private final LocalDateTime now = LocalDateTime.of(2026, 8, 25, 12, 0);

    @Test
    void createsRunningProjectionWithInitialVersion() {
        AgentRunEntity stored = entity("RUNNING", null, null, 0);
        when(mapper.findByScope(1, 2, "run-1")).thenReturn(Optional.of(stored));

        AgentRunCodeSnapshot snapshot = new AgentRunCodeSnapshot("octo/demo", "agent", "a".repeat(40));
        AgentRunView result = service.createRunning("request-1", "run-1", 1, 2, 7, "hello", snapshot, now);

        ArgumentCaptor<AgentRunEntity> inserted = ArgumentCaptor.forClass(AgentRunEntity.class);
        verify(mapper).insert(inserted.capture());
        assertThat(inserted.getValue().getStatus()).isEqualTo("RUNNING");
        assertThat(inserted.getValue().getVersion()).isZero();
        assertThat(inserted.getValue().getFinishedAt()).isNull();
        assertThat(inserted.getValue().getRepositoryFullName()).isEqualTo("octo/demo");
        assertThat(inserted.getValue().getBranchName()).isEqualTo("agent");
        assertThat(inserted.getValue().getCommitSha()).isEqualTo("a".repeat(40));
        assertThat(result.status()).isEqualTo(AgentRunStatus.RUNNING);
    }

    @Test
    void convertsDuplicateIdentityToStableConflict() {
        doThrow(new DuplicateKeyException("duplicate request with private SQL"))
                .when(mapper).insert(any());

        assertThatThrownBy(() -> service.createRunning("request-1", "run-1", 1, 2, 7, "hello", now))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(AgentRunErrorCode.AGENT_RUN_ID_CONFLICT));
    }

    @Test
    void rejectsSecondTerminalTransition() {
        when(mapper.markSucceeded(1, 2, "run-1", "answer", now, 0)).thenReturn(0);

        assertThatThrownBy(() -> service.markSucceeded(1, 2, "run-1", "answer", now))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(AgentRunErrorCode.AGENT_RUN_STATE_CONFLICT));
    }

    @Test
    void scopedGetReturnsStableNotFound() {
        when(mapper.findByScope(1, 2, "missing-run")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.get(1, 2, "missing-run"))
                .isInstanceOfSatisfying(BusinessException.class, error ->
                        assertThat(error.errorCode()).isEqualTo(AgentRunErrorCode.AGENT_RUN_NOT_FOUND));
        verify(mapper).findByScope(1, 2, "missing-run");
    }

    @Test
    void marksFailureUsingOnlyStableKind() {
        when(mapper.markFailed(1, 2, "run-1", "PROTOCOL", now, 0)).thenReturn(1);
        when(mapper.findByScope(1, 2, "run-1"))
                .thenReturn(Optional.of(entity("FAILED", null, "PROTOCOL", 1)));

        AgentRunView result = service.markFailed(1, 2, "run-1", AgentRunFailureKind.PROTOCOL, now);

        assertThat(result.failureKind()).isEqualTo(AgentRunFailureKind.PROTOCOL);
        verify(mapper).markFailed(1, 2, "run-1", "PROTOCOL", now, 0);
    }

    @Test
    void runtimeContextCarriesFrozenCodeSnapshotAndLegacyNullsRemainReadable() {
        when(mapper.findByRunId("run-1")).thenReturn(Optional.of(entity("RUNNING", null, null, 0)));

        AgentRunExecutionContext snapshotContext = service.findByRunIdForRuntime("run-1").orElseThrow();

        assertThat(snapshotContext.repositoryFullName()).isEqualTo("octo/demo");
        assertThat(snapshotContext.branchName()).isEqualTo("agent");
        assertThat(snapshotContext.commitSha()).isEqualTo("a".repeat(40));

        AgentRunEntity legacy = entity("RUNNING", null, null, 0);
        legacy.setRepositoryFullName(null);
        legacy.setBranchName(null);
        legacy.setCommitSha(null);
        when(mapper.findByRunId("legacy-run")).thenReturn(Optional.of(legacy));

        AgentRunExecutionContext legacyContext = service.findByRunIdForRuntime("legacy-run").orElseThrow();
        assertThat(legacyContext.branchName()).isNull();
        assertThat(legacyContext.commitSha()).isNull();
    }

    private AgentRunEntity entity(String status, String output, String failureKind, long version) {
        AgentRunEntity entity = new AgentRunEntity();
        entity.setId(10L);
        entity.setRunId("run-1");
        entity.setRequestId("request-1");
        entity.setWorkspaceId(1);
        entity.setProjectId(2);
        entity.setCreatedBy(7);
        entity.setStatus(status);
        entity.setUserInput("hello");
        entity.setRepositoryFullName("octo/demo");
        entity.setBranchName("agent");
        entity.setCommitSha("a".repeat(40));
        entity.setFinalOutput(output);
        entity.setFailureKind(failureKind);
        entity.setStartedAt(now);
        entity.setFinishedAt("RUNNING".equals(status) ? null : now);
        entity.setCreatedAt(now);
        entity.setUpdatedAt(now);
        entity.setVersion(version);
        return entity;
    }
}
