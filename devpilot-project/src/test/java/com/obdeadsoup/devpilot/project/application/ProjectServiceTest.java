package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.identity.application.UserAccountService;
import com.obdeadsoup.devpilot.project.api.dto.ProjectResponse;
import com.obdeadsoup.devpilot.project.domain.ProjectVisibility;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.error.ProjectErrorCode;
import com.obdeadsoup.devpilot.project.persistence.entity.ProjectEntity;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMemberMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.WorkspaceMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectServiceTest {

    private static final long USER_ID = 42L;
    private static final long WORKSPACE_ID = 100L;
    private static final long PROJECT_ID = 200L;

    private ProjectAuthorizationService authorizationService;
    private ProjectMapper projectMapper;
    private ProjectService service;

    @BeforeEach
    void setUp() {
        CurrentUserProvider currentUserProvider = mock(CurrentUserProvider.class);
        authorizationService = mock(ProjectAuthorizationService.class);
        projectMapper = mock(ProjectMapper.class);
        when(currentUserProvider.requireUserId()).thenReturn(USER_ID);
        service = new ProjectService(
                currentUserProvider,
                mock(UserAccountService.class),
                mock(WorkspaceAuthorizationService.class),
                authorizationService,
                mock(WorkspaceMapper.class),
                projectMapper,
                mock(ProjectMemberMapper.class)
        );
    }

    @Test
    void activatesPlanningProjectAndIncrementsVersion() {
        when(projectMapper.findByScope(WORKSPACE_ID, PROJECT_ID))
                .thenReturn(Optional.of(project("PLANNING", 0)))
                .thenReturn(Optional.of(project("ACTIVE", 1)));
        when(projectMapper.activate(WORKSPACE_ID, PROJECT_ID, 0)).thenReturn(1);

        ProjectResponse response = service.activateProject(WORKSPACE_ID, PROJECT_ID, 0);

        assertThat(response.status().name()).isEqualTo("ACTIVE");
        assertThat(response.version()).isEqualTo(1);
    }

    @Test
    void archiveThenRestoreUseExplicitTransitions() {
        when(projectMapper.findByScope(WORKSPACE_ID, PROJECT_ID))
                .thenReturn(Optional.of(project("ACTIVE", 2)))
                .thenReturn(Optional.of(project("ARCHIVED", 3)))
                .thenReturn(Optional.of(project("ARCHIVED", 3)))
                .thenReturn(Optional.of(project("ACTIVE", 4)));
        when(projectMapper.archive(WORKSPACE_ID, PROJECT_ID, 2)).thenReturn(1);
        when(projectMapper.restore(WORKSPACE_ID, PROJECT_ID, 3)).thenReturn(1);

        assertThat(service.archiveProject(WORKSPACE_ID, PROJECT_ID, 2).status().name())
                .isEqualTo("ARCHIVED");
        assertThat(service.restoreProject(WORKSPACE_ID, PROJECT_ID, 3).status().name())
                .isEqualTo("ACTIVE");
    }

    @Test
    void rejectsInvalidProjectStatusTransition() {
        when(projectMapper.findByScope(WORKSPACE_ID, PROJECT_ID))
                .thenReturn(Optional.of(project("ACTIVE", 1)));

        assertThatThrownBy(() -> service.activateProject(WORKSPACE_ID, PROJECT_ID, 1))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ProjectErrorCode.INVALID_PROJECT_STATUS_TRANSITION));
    }

    @Test
    void explicitActorReadReauthorizesAtCapabilityExecutionPoint() {
        when(projectMapper.findByScope(WORKSPACE_ID, PROJECT_ID))
                .thenReturn(Optional.of(project("ACTIVE", 1)));

        service.getProjectForActor(99L, WORKSPACE_ID, PROJECT_ID);

        verify(authorizationService).requirePermission(
                99L, WORKSPACE_ID, PROJECT_ID, ProjectPermission.PROJECT_READ);
    }

    @Test
    void archivedProjectRejectsOrdinaryProfileUpdate() {
        when(projectMapper.findByScope(WORKSPACE_ID, PROJECT_ID))
                .thenReturn(Optional.of(project("ARCHIVED", 3)));

        assertThatThrownBy(() -> service.updateProjectProfile(
                WORKSPACE_ID, PROJECT_ID, "New name", null, ProjectVisibility.PRIVATE, 3
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_ARCHIVED));
        verify(projectMapper, never()).updateProfile(
                WORKSPACE_ID, PROJECT_ID, "New name", null, "PRIVATE", 3
        );
    }

    @Test
    void staleVersionIsRejectedBeforeConditionalUpdate() {
        when(projectMapper.findByScope(WORKSPACE_ID, PROJECT_ID))
                .thenReturn(Optional.of(project("PLANNING", 4)));

        assertThatThrownBy(() -> service.activateProject(WORKSPACE_ID, PROJECT_ID, 3))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ProjectErrorCode.PROJECT_VERSION_CONFLICT));
        verify(projectMapper, never()).activate(WORKSPACE_ID, PROJECT_ID, 3);
    }

    private ProjectEntity project(String status, long version) {
        return new ProjectEntity(
                PROJECT_ID, WORKSPACE_ID, "DevPilot", "DEV", null,
                status, "PRIVATE", USER_ID, null, null, version, false
        );
    }
}
