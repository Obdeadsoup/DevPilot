package com.obdeadsoup.devpilot.github.domain;

public enum GitHubDeliveryStatus {
    RECEIVED,
    PROCESSING,
    SUCCEEDED,
    FAILED,
    RETRY_WAIT,
    DEAD
}
