package com.obdeadsoup.devpilot.github.domain;

public enum GitHubSyncRunStatus {
    PENDING,
    RUNNING,
    RETRY_WAIT,
    SUCCEEDED,
    DEAD
}
