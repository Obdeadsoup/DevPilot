package com.obdeadsoup.devpilot.project.api.dto;

import com.obdeadsoup.devpilot.project.domain.WorkspaceMemberStatus;
import com.obdeadsoup.devpilot.project.domain.WorkspaceRole;
import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceInvitationEntity;

public record WorkspaceInvitationResponse(
        long workspaceId,
        String workspaceName,
        String workspaceSlug,
        WorkspaceRole role,
        WorkspaceMemberStatus status,
        long invitedBy,
        long version
) {
    public static WorkspaceInvitationResponse from(WorkspaceInvitationEntity entity) {
        return new WorkspaceInvitationResponse(
                entity.workspaceId(),
                entity.workspaceName(),
                entity.workspaceSlug(),
                WorkspaceRole.valueOf(entity.role()),
                WorkspaceMemberStatus.valueOf(entity.status()),
                entity.invitedBy(),
                entity.version()
        );
    }
}
