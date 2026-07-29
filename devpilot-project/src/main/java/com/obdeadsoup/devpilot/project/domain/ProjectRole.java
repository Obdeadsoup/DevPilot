package com.obdeadsoup.devpilot.project.domain;

import java.util.EnumSet;
import java.util.Set;

public enum ProjectRole {

    PROJECT_ADMIN(Set.copyOf(EnumSet.allOf(ProjectPermission.class))),
    DEVELOPER(Set.of(
            ProjectPermission.PROJECT_READ,
            ProjectPermission.PROJECT_UPDATE,
            ProjectPermission.PROJECT_MEMBER_LIST,
            ProjectPermission.PROJECT_ACTIVITY_READ,
            ProjectPermission.REPOSITORY_READ,
            ProjectPermission.REPOSITORY_UPDATE,
            ProjectPermission.TASK_READ,
            ProjectPermission.TASK_CREATE,
            ProjectPermission.TASK_UPDATE,
            ProjectPermission.TASK_ASSIGN,
            ProjectPermission.TASK_STATUS_CHANGE,
            ProjectPermission.AGENT_READ,
            ProjectPermission.AGENT_PROPOSE
    )),
    VIEWER(Set.of(
            ProjectPermission.PROJECT_READ,
            ProjectPermission.PROJECT_MEMBER_LIST,
            ProjectPermission.PROJECT_ACTIVITY_READ,
            ProjectPermission.REPOSITORY_READ,
            ProjectPermission.TASK_READ,
            ProjectPermission.AGENT_READ
    ));

    private final Set<ProjectPermission> permissions;

    ProjectRole(Set<ProjectPermission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<ProjectPermission> permissions() {
        return permissions;
    }

    public boolean hasPermission(ProjectPermission permission) {
        return permissions.contains(permission);
    }
}
