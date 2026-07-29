package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.identity.application.WorkspaceAuthorizationService;
import com.obdeadsoup.devpilot.identity.domain.WorkspaceRole;
import com.obdeadsoup.devpilot.project.domain.ProjectAccess;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.domain.ProjectRole;
import com.obdeadsoup.devpilot.project.persistence.entity.ProjectEntity;
import com.obdeadsoup.devpilot.project.persistence.entity.ProjectMemberEntity;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMemberMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ProjectPermissionMatrixTest {

    private static final long USER_ID = 42L;
    private static final long WORKSPACE_ID = 100L;
    private static final long PROJECT_ID = 200L;

    private ProjectMapper projectMapper;
    private ProjectMemberMapper memberMapper;
    private WorkspaceAuthorizationService workspaceAuthorization;
    private ProjectAuthorizationService authorization;

    @BeforeEach
    void setUp() {
        projectMapper = mock(ProjectMapper.class);
        memberMapper = mock(ProjectMemberMapper.class);
        workspaceAuthorization = mock(WorkspaceAuthorizationService.class);
        authorization = new ProjectAuthorizationService(
                projectMapper, memberMapper, workspaceAuthorization
        );
    }

    @Test
    void workspaceOwnerHasProjectAdminPermissionsForEveryScopedProject() {
        givenProject("ACTIVE", "PRIVATE");
        givenWorkspaceRole(WorkspaceRole.OWNER);

        ProjectAccess access = authorization
                .getEffectiveProjectAccess(USER_ID, WORKSPACE_ID, PROJECT_ID)
                .orElseThrow();

        assertThat(access.source()).isEqualTo(ProjectAccess.Source.WORKSPACE_ADMINISTRATION);
        assertThat(access.permissions()).containsExactlyInAnyOrder(ProjectPermission.values());
    }

    @Test
    void workspaceAdminHasDailyProjectAdministration() {
        givenProject("ACTIVE", "PRIVATE");
        givenWorkspaceRole(WorkspaceRole.ADMIN);

        assertThat(authorization.hasPermission(
                USER_ID, WORKSPACE_ID, PROJECT_ID, ProjectPermission.PROJECT_MEMBER_MANAGE
        )).isTrue();
        assertThat(authorization.hasPermission(
                USER_ID, WORKSPACE_ID, PROJECT_ID, ProjectPermission.PROJECT_ARCHIVE
        )).isTrue();
    }

    @Test
    void privateProjectRejectsWorkspaceMemberWithoutProjectMembership() {
        givenProject("ACTIVE", "PRIVATE");
        givenWorkspaceRole(WorkspaceRole.MEMBER);
        when(memberMapper.findByScopeAndUser(WORKSPACE_ID, PROJECT_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThat(authorization.getEffectiveProjectAccess(
                USER_ID, WORKSPACE_ID, PROJECT_ID
        )).isEmpty();
    }

    @Test
    void internalProjectGivesWorkspaceMemberReadButNotWrite() {
        givenProject("ACTIVE", "INTERNAL");
        givenWorkspaceRole(WorkspaceRole.MEMBER);
        when(memberMapper.findByScopeAndUser(WORKSPACE_ID, PROJECT_ID, USER_ID))
                .thenReturn(Optional.empty());

        assertThat(authorization.hasPermission(
                USER_ID, WORKSPACE_ID, PROJECT_ID, ProjectPermission.PROJECT_ACTIVITY_READ
        )).isTrue();
        assertThat(authorization.hasPermission(
                USER_ID, WORKSPACE_ID, PROJECT_ID, ProjectPermission.PROJECT_UPDATE
        )).isFalse();
    }

    @Test
    void projectRolesMapToExplicitImmutablePermissionSets() {
        assertThat(ProjectRole.PROJECT_ADMIN.permissions())
                .containsExactlyInAnyOrder(ProjectPermission.values());
        assertThat(ProjectRole.DEVELOPER.permissions())
                .contains(ProjectPermission.PROJECT_UPDATE, ProjectPermission.TASK_CREATE)
                .doesNotContain(
                        ProjectPermission.PROJECT_MEMBER_MANAGE,
                        ProjectPermission.TASK_DELETE,
                        ProjectPermission.AGENT_EXECUTE_CONFIRMED
                );
        assertThat(ProjectRole.VIEWER.permissions()).containsExactlyInAnyOrder(
                ProjectPermission.PROJECT_READ,
                ProjectPermission.PROJECT_MEMBER_LIST,
                ProjectPermission.PROJECT_ACTIVITY_READ,
                ProjectPermission.REPOSITORY_READ,
                ProjectPermission.TASK_READ,
                ProjectPermission.AGENT_READ
        );
    }

    @Test
    void activeProjectMemberUsesAssignedRole() {
        givenProject("ACTIVE", "PRIVATE");
        givenWorkspaceRole(WorkspaceRole.MEMBER);
        when(memberMapper.findByScopeAndUser(WORKSPACE_ID, PROJECT_ID, USER_ID))
                .thenReturn(Optional.of(member(ProjectRole.DEVELOPER)));

        assertThat(authorization.hasPermission(
                USER_ID, WORKSPACE_ID, PROJECT_ID, ProjectPermission.TASK_CREATE
        )).isTrue();
        assertThat(authorization.hasPermission(
                USER_ID, WORKSPACE_ID, PROJECT_ID, ProjectPermission.PROJECT_MEMBER_MANAGE
        )).isFalse();
    }

    @Test
    void archivedProjectKeepsReadsAndRejectsWrites() {
        givenProject("ARCHIVED", "PRIVATE");
        givenWorkspaceRole(WorkspaceRole.OWNER);

        assertThat(authorization.hasPermission(
                USER_ID, WORKSPACE_ID, PROJECT_ID, ProjectPermission.PROJECT_ACTIVITY_READ
        )).isTrue();
        assertThat(authorization.hasPermission(
                USER_ID, WORKSPACE_ID, PROJECT_ID, ProjectPermission.PROJECT_UPDATE
        )).isFalse();
        assertThat(authorization.hasPermission(
                USER_ID, WORKSPACE_ID, PROJECT_ID, ProjectPermission.PROJECT_MEMBER_MANAGE
        )).isFalse();
    }

    private void givenProject(String status, String visibility) {
        when(projectMapper.findByScope(WORKSPACE_ID, PROJECT_ID))
                .thenReturn(Optional.of(new ProjectEntity(
                        PROJECT_ID, WORKSPACE_ID, status, visibility, 0L, false
                )));
    }

    private void givenWorkspaceRole(WorkspaceRole role) {
        when(workspaceAuthorization.getEffectiveRole(USER_ID, WORKSPACE_ID))
                .thenReturn(Optional.of(role));
    }

    private ProjectMemberEntity member(ProjectRole role) {
        return new ProjectMemberEntity(
                1L, WORKSPACE_ID, PROJECT_ID, USER_ID, role.name(), "ACTIVE", 1L, 0L
        );
    }
}
