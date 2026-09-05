package com.obdeadsoup.devpilot.agent.application.proposal;

import com.obdeadsoup.devpilot.agent.application.*;
import com.obdeadsoup.devpilot.agent.config.AgentProposalProperties;
import com.obdeadsoup.devpilot.agent.persistence.entity.AgentToolProposalEntity;
import com.obdeadsoup.devpilot.agent.persistence.mapper.AgentToolProposalMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.task.api.dto.TaskResponse;
import com.obdeadsoup.devpilot.task.application.TaskApplicationService;
import com.obdeadsoup.devpilot.task.application.TaskAuthorizationService;
import com.obdeadsoup.devpilot.task.domain.*;
import com.obdeadsoup.devpilot.task.error.TaskErrorCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentToolProposalServiceTest {
    private final AgentToolProposalMapper mapper = mock(AgentToolProposalMapper.class);
    private final AgentRunExecutionContextQuery contexts = mock(AgentRunExecutionContextQuery.class);
    private final AgentRunPersistenceService runs = mock(AgentRunPersistenceService.class);
    private final ProjectAuthorizationService projectAuth = mock(ProjectAuthorizationService.class);
    private final TaskAuthorizationService taskAuth = mock(TaskAuthorizationService.class);
    private final TaskApplicationService tasks = mock(TaskApplicationService.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-09-05T00:00:00Z"), ZoneOffset.UTC);
    private AgentToolProposalService service;

    @BeforeEach void setUp() {
        service = new AgentToolProposalService(mapper, contexts, runs, projectAuth, taskAuth, tasks,
                new AgentProposalProperties(Duration.ofMinutes(15), 100), clock);
    }

    @Test void createFreezesCanonicalPayloadAndMovesRunToWaiting() {
        when(contexts.findByRunIdForRuntime("run")).thenReturn(Optional.of(context(AgentRunStatus.RUNNING, 0)));
        when(mapper.findByCall("run", "call")).thenReturn(Optional.empty());
        ArgumentCaptor<AgentToolProposalEntity> saved = ArgumentCaptor.forClass(AgentToolProposalEntity.class);
        when(mapper.insert(saved.capture())).thenAnswer(invocation -> {
            saved.getValue().setCreatedAt(LocalDateTime.now(clock)); return 1;
        });
        when(mapper.findById(anyString())).thenAnswer(invocation -> Optional.of(saved.getValue()));

        AgentToolProposalView result = service.create(new CreateAgentToolProposalCommand(
                "request", "run", "call", "task.create",
                new HashMap<>(Map.of("title", "  Exact   title  ", "priority", "HIGH", "assigneeUserId", 7.0))));

        assertEquals(Map.of("title", "Exact title", "priority", "HIGH", "assigneeUserId", 7), result.arguments());
        assertEquals("proposal:" + result.proposalId(), result.idempotencyKey());
        assertEquals(64, result.payloadHash().length());
        verify(taskAuth).requirePermission(3, 1, 2, ProjectPermission.TASK_CREATE);
        verify(taskAuth).requireEligibleAssignee(7, 1, 2);
        verify(runs).markWaitingApproval("run", 0);
        verifyNoInteractions(tasks);
    }

    @Test void duplicateApproveExecutesExactPayloadOnlyOnceAndReturnsOriginalResult() {
        AgentToolProposalEntity entity = pending();
        when(mapper.lockById("proposal")).thenReturn(Optional.of(entity));
        when(mapper.transition("proposal", "PENDING_APPROVAL", "EXECUTING", LocalDateTime.now(clock), 0)).thenReturn(1);
        when(tasks.createTaskAs(eq(3L), eq(1L), eq(2L), any())).thenReturn(task());
        when(mapper.markExecuted(eq("proposal"), anyString(), eq("42"), eq(LocalDateTime.now(clock)), eq(1L)))
                .thenAnswer(invocation -> { entity.setStatus("EXECUTED"); entity.setExecutionResult(invocation.getArgument(1));
                    entity.setResourceId("42"); entity.setVersion(2); return 1; });
        when(mapper.findById("proposal")).thenReturn(Optional.of(entity));
        when(contexts.findByRunIdForRuntime("run")).thenReturn(Optional.of(context(AgentRunStatus.WAITING_APPROVAL, 1)));

        AgentToolProposalDecisionResult first = service.decide(3, 1, 2, "run", "proposal",
                AgentToolProposalDecision.APPROVE);
        AgentToolProposalDecisionResult duplicate = service.decide(3, 1, 2, "run", "proposal",
                AgentToolProposalDecision.APPROVE);

        assertTrue(first.resumeRequired()); assertFalse(duplicate.resumeRequired());
        assertEquals(AgentToolProposalStatus.EXECUTED, duplicate.proposal().status());
        ArgumentCaptor<com.obdeadsoup.devpilot.task.application.command.CreateTaskCommand> command =
                ArgumentCaptor.forClass(com.obdeadsoup.devpilot.task.application.command.CreateTaskCommand.class);
        verify(tasks, times(1)).createTaskAs(eq(3L), eq(1L), eq(2L), command.capture());
        assertEquals("Exact title", command.getValue().title());
        verify(runs, times(1)).markRunningAfterApproval("run", 1);
    }

    @Test void expiredProposalCannotExecuteAndResumesWithExpiredDecision() {
        AgentToolProposalEntity entity = pending(); entity.setExpiresAt(LocalDateTime.now(clock).minusSeconds(1));
        when(mapper.lockById("proposal")).thenReturn(Optional.of(entity));
        when(mapper.transition("proposal", "PENDING_APPROVAL", "EXPIRED", LocalDateTime.now(clock), 0))
                .thenAnswer(inv -> { entity.setStatus("EXPIRED"); entity.setVersion(1); return 1; });
        when(mapper.findById("proposal")).thenReturn(Optional.of(entity));
        when(contexts.findByRunIdForRuntime("run")).thenReturn(Optional.of(context(AgentRunStatus.WAITING_APPROVAL, 1)));
        assertEquals(AgentToolProposalStatus.EXPIRED,
                service.decide(3, 1, 2, "run", "proposal", AgentToolProposalDecision.APPROVE).proposal().status());
        verifyNoInteractions(tasks); verify(runs).markRunningAfterApproval("run", 1);
    }

    @Test void revokedPermissionMakesApproveFailBeforeSideEffect() {
        AgentToolProposalEntity entity = pending();
        when(mapper.lockById("proposal")).thenReturn(Optional.of(entity));
        when(contexts.findByRunIdForRuntime("run")).thenReturn(Optional.of(context(AgentRunStatus.WAITING_APPROVAL, 1)));
        doThrow(new BusinessException(TaskErrorCode.TASK_PERMISSION_DENIED)).when(projectAuth)
                .requirePermission(3, 1, 2, ProjectPermission.AGENT_PROPOSE);
        assertThrows(BusinessException.class, () -> service.decide(
                3, 1, 2, "run", "proposal", AgentToolProposalDecision.APPROVE));
        verifyNoInteractions(tasks); verify(mapper, never()).transition(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test void approveRejectCasLossCannotExecute() {
        AgentToolProposalEntity entity = pending();
        when(mapper.lockById("proposal")).thenReturn(Optional.of(entity));
        when(contexts.findByRunIdForRuntime("run")).thenReturn(Optional.of(context(AgentRunStatus.WAITING_APPROVAL, 1)));
        when(mapper.transition(anyString(), anyString(), anyString(), any(), anyLong())).thenReturn(0);
        assertThrows(BusinessException.class, () -> service.decide(
                3, 1, 2, "run", "proposal", AgentToolProposalDecision.REJECT));
        verifyNoInteractions(tasks);
    }

    @Test void cancelledWaitingRunCannotApproveOrExecute() {
        AgentToolProposalEntity entity = pending();
        when(mapper.lockById("proposal")).thenReturn(Optional.of(entity));
        when(contexts.findByRunIdForRuntime("run")).thenReturn(Optional.of(context(AgentRunStatus.CANCELLED, 2)));

        assertThrows(BusinessException.class, () -> service.decide(
                3, 1, 2, "run", "proposal", AgentToolProposalDecision.APPROVE));

        verifyNoInteractions(tasks);
        verify(runs, never()).markRunningAfterApproval(anyString(), anyLong());
        verify(mapper, never()).transition(anyString(), anyString(), anyString(), any(), anyLong());
    }

    @Test void expirationAfterCancellationDoesNotRestartRun() {
        AgentToolProposalEntity entity = pending();
        entity.setExpiresAt(LocalDateTime.now(clock).minusSeconds(1));
        when(mapper.findExpired(LocalDateTime.now(clock), 100)).thenReturn(List.of("proposal"));
        when(mapper.lockById("proposal")).thenReturn(Optional.of(entity));
        when(mapper.transition("proposal", "PENDING_APPROVAL", "EXPIRED", LocalDateTime.now(clock), 0))
                .thenAnswer(inv -> { entity.setStatus("EXPIRED"); entity.setVersion(1); return 1; });
        when(mapper.findById("proposal")).thenReturn(Optional.of(entity));
        when(contexts.findByRunIdForRuntime("run")).thenReturn(Optional.of(context(AgentRunStatus.CANCELLED, 2)));

        List<AgentToolProposalDecisionResult> results = service.expireDue();

        assertEquals(1, results.size());
        assertEquals(AgentToolProposalStatus.EXPIRED, results.getFirst().proposal().status());
        assertFalse(results.getFirst().resumeRequired());
        verify(runs, never()).markRunningAfterApproval(anyString(), anyLong());
    }

    private AgentRunExecutionContext context(AgentRunStatus status, long version) {
        return new AgentRunExecutionContext("run", "request", 1, 2, 3, status, null, null, null, version);
    }
    private AgentToolProposalEntity pending() {
        AgentToolProposalEntity p = new AgentToolProposalEntity(); p.setProposalId("proposal"); p.setRunId("run");
        p.setActorId(3); p.setWorkspaceId(1); p.setProjectId(2); p.setToolCallId("call"); p.setToolName("task.create");
        p.setCanonicalArguments("{\"priority\":\"MEDIUM\",\"title\":\"Exact title\"}"); p.setPayloadHash("a".repeat(64));
        p.setIdempotencyKey("proposal:proposal"); p.setStatus("PENDING_APPROVAL");
        p.setCreatedAt(LocalDateTime.now(clock)); p.setExpiresAt(LocalDateTime.now(clock).plusMinutes(5)); p.setVersion(0);
        return p;
    }
    private TaskResponse task() {
        return new TaskResponse(42, "DP-42", "Exact title", null, TaskStatus.BACKLOG, TaskPriority.MEDIUM,
                3, null, null, null, null, LocalDateTime.now(clock), LocalDateTime.now(clock), 0);
    }
}
