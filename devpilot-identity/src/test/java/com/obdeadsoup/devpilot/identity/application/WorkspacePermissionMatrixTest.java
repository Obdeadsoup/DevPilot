package com.obdeadsoup.devpilot.identity.application;

import com.obdeadsoup.devpilot.identity.domain.WorkspacePermission;
import com.obdeadsoup.devpilot.identity.domain.WorkspaceRole;
import com.obdeadsoup.devpilot.identity.persistence.entity.WorkspaceEntity;
import com.obdeadsoup.devpilot.identity.persistence.entity.WorkspaceMemberEntity;
import com.obdeadsoup.devpilot.identity.persistence.mapper.WorkspaceMapper;
import com.obdeadsoup.devpilot.identity.persistence.mapper.WorkspaceMemberMapper;
import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class WorkspacePermissionMatrixTest {

    private static final long WORKSPACE_ID = 100L;
    private static final long USER_ID = 42L;

    @Test
    void ownerHasEveryWorkspacePermission() {
        assertThat(WorkspaceRole.OWNER.permissions())
                .containsExactlyInAnyOrder(WorkspacePermission.values());
    }

    @Test
    void adminCannotDeleteOrTransferOwnership() {
        assertThat(WorkspaceRole.ADMIN.hasPermission(WorkspacePermission.WORKSPACE_UPDATE)).isTrue();
        assertThat(WorkspaceRole.ADMIN.hasPermission(WorkspacePermission.WORKSPACE_MEMBER_INVITE)).isTrue();
        assertThat(WorkspaceRole.ADMIN.hasPermission(WorkspacePermission.WORKSPACE_DELETE)).isFalse();
        assertThat(WorkspaceRole.ADMIN.hasPermission(
                WorkspacePermission.WORKSPACE_TRANSFER_OWNERSHIP
        )).isFalse();
    }

    @Test
    void memberHasNoWorkspaceMemberManagementPermission() {
        assertThat(WorkspaceRole.MEMBER.hasPermission(WorkspacePermission.WORKSPACE_READ)).isTrue();
        assertThat(WorkspaceRole.MEMBER.hasPermission(WorkspacePermission.PROJECT_CREATE)).isTrue();
        assertThat(WorkspaceRole.MEMBER.hasPermission(
                WorkspacePermission.WORKSPACE_MEMBER_INVITE
        )).isFalse();
        assertThat(WorkspaceRole.MEMBER.hasPermission(
                WorkspacePermission.WORKSPACE_MEMBER_ROLE_UPDATE
        )).isFalse();
        assertThat(WorkspaceRole.MEMBER.hasPermission(
                WorkspacePermission.WORKSPACE_MEMBER_REMOVE
        )).isFalse();
    }

    @Test
    void viewerOnlyHasReadPermissions() {
        assertThat(WorkspaceRole.VIEWER.permissions()).containsExactlyInAnyOrder(
                WorkspacePermission.WORKSPACE_READ,
                WorkspacePermission.WORKSPACE_MEMBER_LIST
        );
    }

    @Test
    void invitedSuspendedRemovedAndDisabledWorkspaceHaveNoPermission() {
        WorkspaceMapper workspaceMapper = mock(WorkspaceMapper.class);
        WorkspaceMemberMapper memberMapper = mock(WorkspaceMemberMapper.class);
        WorkspaceAuthorizationService service =
                new WorkspaceAuthorizationService(workspaceMapper, memberMapper);
        when(workspaceMapper.findById(WORKSPACE_ID))
                .thenReturn(Optional.of(workspace("ACTIVE")));

        for (String status : new String[]{"INVITED", "SUSPENDED", "REMOVED"}) {
            when(memberMapper.findByWorkspaceAndUser(WORKSPACE_ID, USER_ID))
                    .thenReturn(Optional.of(member(status)));
            assertThat(service.hasPermission(
                    USER_ID, WORKSPACE_ID, WorkspacePermission.WORKSPACE_READ
            )).isFalse();
        }

        when(workspaceMapper.findById(WORKSPACE_ID))
                .thenReturn(Optional.of(workspace("DISABLED")));
        assertThat(service.getEffectiveRole(USER_ID, WORKSPACE_ID)).isEmpty();
    }

    private WorkspaceEntity workspace(String status) {
        return new WorkspaceEntity(WORKSPACE_ID, 1L, status, 0L, false);
    }

    private WorkspaceMemberEntity member(String status) {
        return new WorkspaceMemberEntity(
                1L, WORKSPACE_ID, USER_ID, WorkspaceRole.MEMBER.name(), status, 1L, null, 0L
        );
    }
}
