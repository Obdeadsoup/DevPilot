package com.obdeadsoup.devpilot.outbox.domain;

public enum OutboxFailureType {
    MALFORMED_PAYLOAD(false),
    UNSUPPORTED_SCHEMA(false),
    UNKNOWN_EVENT_TYPE(false),
    INVALID_EVENT(false),
    SCOPE_CONFLICT(false),
    TRANSIENT_DATABASE(true),
    TRANSIENT_HANDLER(true),
    PROCESSING_TIMEOUT(true);

    private final boolean retryable;

    OutboxFailureType(boolean retryable) {
        this.retryable = retryable;
    }

    public boolean retryable() {
        return retryable;
    }
}
