package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.agent.infrastructure.grpc.AgentRuntimeClientException;
import com.obdeadsoup.devpilot.agent.infrastructure.grpc.AgentRuntimeFailureKind;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunApplicationServiceTest {
    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 8, 25, 12, 0);
    private static final LocalDateTime FINISHED_AT = STARTED_AT.plusSeconds(2);

    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ProjectAuthorizationService authorizationService = mock(ProjectAuthorizationService.class);
    private final AgentRunPersistenceService persistenceService = mock(AgentRunPersistenceService.class);
    private final AgentRuntimePort runtimePort = mock(AgentRuntimePort.class);
    private final AgentRunIdentityFactory identityFactory = mock(AgentRunIdentityFactory.class);
    private final AgentRunTimeProvider timeProvider = mock(AgentRunTimeProvider.class);
    private AgentRunApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AgentRunApplicationService(currentUserProvider, authorizationService, persistenceService,
                runtimePort, identityFactory, timeProvider);
        when(currentUserProvider.requireUserId()).thenReturn(7L);
        when(identityFactory.create()).thenReturn(new AgentRunIdentity("request-1", "run-1"));
        when(timeProvider.now()).thenReturn(STARTED_AT, FINISHED_AT);
    }

    @Test
    void commitsRunningBeforeRpcAndTerminalStateAfterRpc() {
        AgentRunView succeeded = view(AgentRunStatus.SUCCEEDED, "answer", null);
        when(runtimePort.run(new AgentRunCommand("request-1", "run-1", "hello")))
                .thenReturn(new AgentRunResult("run-1", "answer", AgentRunStatus.SUCCEEDED));
        when(persistenceService.markSucceeded(1, 2, "run-1", "answer", FINISHED_AT))
                .thenReturn(succeeded);

        AgentRunView result = service.start(1, 2, "  hello  ");

        assertThat(result).isEqualTo(succeeded);
        verify(authorizationService).requirePermission(7, 1, 2, ProjectPermission.AGENT_PROPOSE);
        InOrder order = inOrder(persistenceService, runtimePort);
        order.verify(persistenceService).createRunning("request-1", "run-1", 1, 2, 7, "hello", STARTED_AT);
        order.verify(runtimePort).run(new AgentRunCommand("request-1", "run-1", "hello"));
        order.verify(persistenceService).markSucceeded(1, 2, "run-1", "answer", FINISHED_AT);
    }

    @Test
    void projectsExplicitRemoteFailureWithoutPersistingRemoteOutput() {
        AgentRunView failed = view(AgentRunStatus.FAILED, null, AgentRunFailureKind.REMOTE_FAILED);
        when(runtimePort.run(any())).thenReturn(new AgentRunResult("run-1", "private detail", AgentRunStatus.FAILED));
        when(persistenceService.markFailed(1, 2, "run-1", AgentRunFailureKind.REMOTE_FAILED, FINISHED_AT))
                .thenReturn(failed);

        assertThat(service.start(1, 2, "hello")).isEqualTo(failed);
        verify(persistenceService).markFailed(1, 2, "run-1", AgentRunFailureKind.REMOTE_FAILED, FINISHED_AT);
        verify(persistenceService, never()).markSucceeded(eq(1L), eq(2L), eq("run-1"), any(), any());
    }

    @Test
    void mapsSanitizedRuntimeFailureToRetrievableFailedProjection() {
        AgentRunView failed = view(AgentRunStatus.FAILED, null, AgentRunFailureKind.DEADLINE_EXCEEDED);
        when(runtimePort.run(any())).thenThrow(new AgentRuntimeClientException(AgentRuntimeFailureKind.DEADLINE_EXCEEDED));
        when(persistenceService.markFailed(1, 2, "run-1", AgentRunFailureKind.DEADLINE_EXCEEDED, FINISHED_AT))
                .thenReturn(failed);

        AgentRunView result = service.start(1, 2, "hello");

        assertThat(result.failureKind()).isEqualTo(AgentRunFailureKind.DEADLINE_EXCEEDED);
        assertThat(result.finalOutput()).isNull();
    }

    @ParameterizedTest
    @EnumSource(value = AgentRuntimeFailureKind.class, names = {"UNAVAILABLE", "PROTOCOL"})
    void mapsUnavailableAndProtocolFailures(AgentRuntimeFailureKind runtimeKind) {
        AgentRunFailureKind expected = AgentRunFailureKind.fromRuntime(runtimeKind);
        AgentRunView failed = view(AgentRunStatus.FAILED, null, expected);
        when(runtimePort.run(any())).thenThrow(new AgentRuntimeClientException(runtimeKind));
        when(persistenceService.markFailed(1, 2, "run-1", expected, FINISHED_AT)).thenReturn(failed);

        assertThat(service.start(1, 2, "hello").failureKind()).isEqualTo(expected);
    }

    @Test
    void marksUnknownThenRethrowsUnexpectedRuntimeFailure() {
        IllegalStateException failure = new IllegalStateException("programming failure");
        when(runtimePort.run(any())).thenThrow(failure);

        assertThatThrownBy(() -> service.start(1, 2, "hello")).isSameAs(failure);
        verify(persistenceService).markFailed(1, 2, "run-1", AgentRunFailureKind.UNKNOWN, FINISHED_AT);
    }

    @Test
    void checksReadPermissionAndKeepsScopeForGet() {
        AgentRunView succeeded = view(AgentRunStatus.SUCCEEDED, "answer", null);
        when(persistenceService.get(1, 2, "run-1")).thenReturn(succeeded);

        assertThat(service.get(1, 2, "run-1")).isEqualTo(succeeded);
        verify(authorizationService).requirePermission(7, 1, 2, ProjectPermission.AGENT_READ);
        verify(persistenceService).get(1, 2, "run-1");
    }

    @Test
    void authorizationFailureStopsBeforeIdentityAndPersistence() {
        RuntimeException denied = new RuntimeException("denied");
        org.mockito.Mockito.doThrow(denied).when(authorizationService)
                .requirePermission(7, 1, 2, ProjectPermission.AGENT_PROPOSE);

        assertThatThrownBy(() -> service.start(1, 2, "hello")).isSameAs(denied);
        verify(identityFactory, never()).create();
        verify(persistenceService, never()).createRunning(any(), any(), eq(1L), eq(2L), eq(7L), any(), any());
        verify(runtimePort, never()).run(any());
    }

    @Test
    void unauthenticatedRequestStopsBeforeAuthorization() {
        BusinessException unauthenticated = new BusinessException(IdentityErrorCode.AUTHENTICATION_REQUIRED);
        when(currentUserProvider.requireUserId()).thenThrow(unauthenticated);

        assertThatThrownBy(() -> service.start(1, 2, "hello")).isSameAs(unauthenticated);
        verify(authorizationService, never()).requirePermission(anyLong(), anyLong(), anyLong(), any());
        verify(persistenceService, never()).createRunning(any(), any(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    private AgentRunView view(AgentRunStatus status, String output, AgentRunFailureKind failureKind) {
        return new AgentRunView("run-1", "request-1", 1, 2, 7, status, "hello", output, failureKind,
                STARTED_AT, status == AgentRunStatus.RUNNING ? null : FINISHED_AT,
                STARTED_AT, FINISHED_AT, status == AgentRunStatus.RUNNING ? 0 : 1);
    }
}
