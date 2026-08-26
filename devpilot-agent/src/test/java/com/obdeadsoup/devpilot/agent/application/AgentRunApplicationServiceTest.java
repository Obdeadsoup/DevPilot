package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
    private static final LocalDateTime STARTED_AT = LocalDateTime.of(2026, 8, 26, 12, 0);

    private final CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
    private final ProjectAuthorizationService authorizationService = mock(ProjectAuthorizationService.class);
    private final AgentRunPersistenceService persistenceService = mock(AgentRunPersistenceService.class);
    private final AgentRunStreamCoordinator streamCoordinator = mock(AgentRunStreamCoordinator.class);
    private final AgentRunIdentityFactory identityFactory = mock(AgentRunIdentityFactory.class);
    private final AgentRunTimeProvider timeProvider = mock(AgentRunTimeProvider.class);
    private AgentRunApplicationService service;

    @BeforeEach
    void setUp() {
        service = new AgentRunApplicationService(currentUserProvider, authorizationService, persistenceService,
                streamCoordinator, identityFactory, timeProvider);
        when(currentUserProvider.requireUserId()).thenReturn(7L);
        when(identityFactory.create()).thenReturn(new AgentRunIdentity("request-1", "run-1"));
        when(timeProvider.now()).thenReturn(STARTED_AT);
    }

    @Test
    void commitsRunningBeforeStartingStreamAndReturnsRunningWithoutWaiting() {
        AgentRunView running = view();
        when(persistenceService.createRunning(
                "request-1", "run-1", 1, 2, 7, "hello", STARTED_AT)).thenReturn(running);

        AgentRunView result = service.start(1, 2, "  hello  ");

        assertThat(result).isEqualTo(running);
        assertThat(result.status()).isEqualTo(AgentRunStatus.RUNNING);
        verify(authorizationService).requirePermission(7, 1, 2, ProjectPermission.AGENT_PROPOSE);
        InOrder order = inOrder(persistenceService, streamCoordinator);
        order.verify(persistenceService).createRunning(
                "request-1", "run-1", 1, 2, 7, "hello", STARTED_AT);
        order.verify(streamCoordinator).start(
                1, 2, new AgentRunCommand("request-1", "run-1", "hello"));
        verify(persistenceService, never()).markSucceeded(anyLong(), anyLong(), any(), any(), any());
        verify(persistenceService, never()).markFailed(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void checksReadPermissionAndKeepsScopeForGet() {
        AgentRunView running = view();
        when(persistenceService.get(1, 2, "run-1")).thenReturn(running);

        assertThat(service.get(1, 2, "run-1")).isEqualTo(running);
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
        verify(streamCoordinator, never()).start(anyLong(), anyLong(), any());
    }

    @Test
    void unauthenticatedRequestStopsBeforeAuthorization() {
        BusinessException unauthenticated = new BusinessException(IdentityErrorCode.AUTHENTICATION_REQUIRED);
        when(currentUserProvider.requireUserId()).thenThrow(unauthenticated);

        assertThatThrownBy(() -> service.start(1, 2, "hello")).isSameAs(unauthenticated);
        verify(authorizationService, never()).requirePermission(anyLong(), anyLong(), anyLong(), any());
        verify(persistenceService, never()).createRunning(any(), any(), anyLong(), anyLong(), anyLong(), any(), any());
    }

    private AgentRunView view() {
        return new AgentRunView("run-1", "request-1", 1, 2, 7, AgentRunStatus.RUNNING,
                "hello", null, null, STARTED_AT, null, STARTED_AT, STARTED_AT, 0);
    }
}
