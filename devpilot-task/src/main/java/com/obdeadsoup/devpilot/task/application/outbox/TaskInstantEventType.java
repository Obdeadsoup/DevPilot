package com.obdeadsoup.devpilot.task.application.outbox;

public enum TaskInstantEventType {
    TASK_ASSIGNED_V1("assigned"),
    TASK_UNASSIGNED_V1("unassigned"),
    TASK_SUBMITTED_FOR_REVIEW_V1("submitted-for-review"),
    TASK_CHANGES_REQUESTED_V1("changes-requested"),
    TASK_COMPLETED_V1("completed"),
    TASK_REOPENED_V1("reopened");

    private final String eventKeySuffix;

    TaskInstantEventType(String eventKeySuffix) {
        this.eventKeySuffix = eventKeySuffix;
    }

    public String eventKeySuffix() {
        return eventKeySuffix;
    }
}
