package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.identity.application.UserAccountService;
import com.obdeadsoup.devpilot.project.api.dto.WorkspaceResponse;
import com.obdeadsoup.devpilot.project.error.WorkspaceErrorCode;
import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceEntity;
import com.obdeadsoup.devpilot.project.persistence.mapper.WorkspaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class WorkspaceServiceTest {

    private static final long USER_ID = 42L;
    private static final long WORKSPACE_ID = 100L;

    private CurrentUserProvider currentUserProvider;
    private WorkspaceMapper workspaceMapper;
    private WorkspaceService service;

    @BeforeEach
    void setUp() {
        currentUserProvider = mock(CurrentUserProvider.class);
        workspaceMapper = mock(WorkspaceMapper.class);
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        service = new WorkspaceService(
                currentUserProvider,
                mock(UserAccountService.class),
                mock(WorkspaceAuthorizationService.class),
                workspaceMapper
        );
    }

    @Test
    void ownerDisablesActiveWorkspaceWithExpectedVersion() {
        when(workspaceMapper.findById(WORKSPACE_ID))
                .thenReturn(Optional.of(workspace("ACTIVE", 3)))
                .thenReturn(Optional.of(workspace("DISABLED", 4)));
        when(workspaceMapper.disable(WORKSPACE_ID, USER_ID, 3)).thenReturn(1);

        WorkspaceResponse response = service.disableWorkspace(WORKSPACE_ID, 3);

        assertThat(response.status().name()).isEqualTo("DISABLED");
        assertThat(response.version()).isEqualTo(4);
        verify(workspaceMapper).disable(WORKSPACE_ID, USER_ID, 3);
    }

    @Test
    void ownerReactivatesDisabledWorkspaceWithExpectedVersion() {
        when(workspaceMapper.findById(WORKSPACE_ID))
                .thenReturn(Optional.of(workspace("DISABLED", 4)))
                .thenReturn(Optional.of(workspace("ACTIVE", 5)));
        when(workspaceMapper.reactivate(WORKSPACE_ID, USER_ID, 4)).thenReturn(1);

        WorkspaceResponse response = service.reactivateWorkspace(WORKSPACE_ID, 4);

        assertThat(response.status().name()).isEqualTo("ACTIVE");
        assertThat(response.version()).isEqualTo(5);
    }

    @Test
    void rejectsInvalidWorkspaceStatusTransition() {
        when(workspaceMapper.findById(WORKSPACE_ID))
                .thenReturn(Optional.of(workspace("DISABLED", 4)));

        assertThatThrownBy(() -> service.disableWorkspace(WORKSPACE_ID, 4))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(WorkspaceErrorCode.INVALID_WORKSPACE_STATUS_TRANSITION));
    }

    @Test
    void zeroRowTransitionIsVersionConflict() {
        when(workspaceMapper.findById(WORKSPACE_ID))
                .thenReturn(Optional.of(workspace("ACTIVE", 3)));
        when(workspaceMapper.disable(WORKSPACE_ID, USER_ID, 3)).thenReturn(0);

        assertThatThrownBy(() -> service.disableWorkspace(WORKSPACE_ID, 3))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(WorkspaceErrorCode.WORKSPACE_VERSION_CONFLICT));
    }

    private WorkspaceEntity workspace(String status, long version) {
        return new WorkspaceEntity(
                WORKSPACE_ID, "DevPilot", "devpilot", null, USER_ID,
                status, version, null, null, false
        );
    }
}
