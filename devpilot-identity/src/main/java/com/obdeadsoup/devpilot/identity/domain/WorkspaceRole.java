package com.obdeadsoup.devpilot.identity.domain;

import java.util.EnumSet;
import java.util.Set;

public enum WorkspaceRole {

    OWNER(Set.copyOf(EnumSet.allOf(WorkspacePermission.class))),
    ADMIN(Set.of(
            WorkspacePermission.WORKSPACE_READ,
            WorkspacePermission.WORKSPACE_UPDATE,
            WorkspacePermission.WORKSPACE_MEMBER_LIST,
            WorkspacePermission.WORKSPACE_MEMBER_INVITE,
            WorkspacePermission.WORKSPACE_MEMBER_ROLE_UPDATE,
            WorkspacePermission.WORKSPACE_MEMBER_REMOVE,
            WorkspacePermission.PROJECT_CREATE,
            WorkspacePermission.WORKSPACE_AUDIT_READ,
            WorkspacePermission.WORKSPACE_AGENT_POLICY_MANAGE
    )),
    MEMBER(Set.of(
            WorkspacePermission.WORKSPACE_READ,
            WorkspacePermission.WORKSPACE_MEMBER_LIST,
            WorkspacePermission.PROJECT_CREATE
    )),
    VIEWER(Set.of(
            WorkspacePermission.WORKSPACE_READ,
            WorkspacePermission.WORKSPACE_MEMBER_LIST
    ));

    private final Set<WorkspacePermission> permissions;

    WorkspaceRole(Set<WorkspacePermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<WorkspacePermission> permissions() {
        return permissions;
    }

    public boolean hasPermission(WorkspacePermission permission) {
        return permissions.contains(permission);
    }
}
