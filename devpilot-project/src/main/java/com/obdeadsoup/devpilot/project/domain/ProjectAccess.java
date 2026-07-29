package com.obdeadsoup.devpilot.project.domain;

import java.util.Set;

public record ProjectAccess(
        ProjectRole effectiveRole,
        Source source,
        Set<ProjectPermission> permissions
) {

    public ProjectAccess {
        permissions = Set.copyOf(permissions);
    }

    public enum Source {
        WORKSPACE_ADMINISTRATION,
        PROJECT_MEMBERSHIP,
        INTERNAL_WORKSPACE_MEMBERSHIP
    }

    public boolean hasPermission(ProjectPermission permission) {
        return permissions.contains(permission);
    }
}
