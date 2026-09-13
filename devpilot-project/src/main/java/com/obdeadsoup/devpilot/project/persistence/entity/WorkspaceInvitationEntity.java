package com.obdeadsoup.devpilot.project.persistence.entity;

public record WorkspaceInvitationEntity(
        long workspaceId,
        String workspaceName,
        String workspaceSlug,
        String role,
        String status,
        long invitedBy,
        long version
) {
}
