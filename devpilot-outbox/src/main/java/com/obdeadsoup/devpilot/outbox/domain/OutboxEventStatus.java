package com.obdeadsoup.devpilot.outbox.domain;

public enum OutboxEventStatus {
    PENDING,
    PROCESSING,
    RETRY_WAIT,
    PROCESSED,
    DEAD
}
