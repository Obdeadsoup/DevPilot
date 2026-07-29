package com.obdeadsoup.devpilot.project.domain;

public enum ProjectPermission {

    PROJECT_READ(true),
    PROJECT_UPDATE(false),
    PROJECT_ARCHIVE(false),
    PROJECT_MEMBER_LIST(true),
    PROJECT_MEMBER_MANAGE(false),
    PROJECT_ACTIVITY_READ(true),
    REPOSITORY_READ(true),
    REPOSITORY_BIND(false),
    REPOSITORY_UPDATE(false),
    REPOSITORY_UNBIND(false),
    TASK_READ(true),
    TASK_CREATE(false),
    TASK_UPDATE(false),
    TASK_ASSIGN(false),
    TASK_STATUS_CHANGE(false),
    TASK_DELETE(false),
    AGENT_READ(true),
    AGENT_PROPOSE(false),
    AGENT_EXECUTE_CONFIRMED(false),
    PROJECT_AUDIT_READ(true);

    private final boolean readOnly;

    ProjectPermission(boolean readOnly) {
        this.readOnly = readOnly;
    }

    public boolean isReadOnly() {
        return readOnly;
    }
}
