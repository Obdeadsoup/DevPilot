package com.obdeadsoup.devpilot.outbox.application;

import com.obdeadsoup.devpilot.outbox.domain.OutboxFailureType;

public record OutboxFailureDecision(
        OutboxFailureType failureType, boolean retryable, String errorCode, String safeMessage) {
}
