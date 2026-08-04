package com.obdeadsoup.devpilot.task.domain;

public enum TaskStatus {
    BACKLOG, TODO, IN_PROGRESS, IN_REVIEW, DONE, CANCELED;

    public boolean isTerminal() {
        return this == DONE || this == CANCELED;
    }
}
