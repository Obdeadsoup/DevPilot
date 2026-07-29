package com.obdeadsoup.devpilot.identity.application;

/**
 * Identity-owned port used to revoke project-scoped access when a workspace membership ends.
 * Its implementation belongs to the project module, preserving the project-to-identity direction.
 */
public interface WorkspaceProjectMembershipRevoker {

    void revokeAllForWorkspaceUser(long workspaceId, long userId);
}
