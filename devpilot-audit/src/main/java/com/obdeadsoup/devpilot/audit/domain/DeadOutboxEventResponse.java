package com.obdeadsoup.devpilot.audit.domain;

import java.time.LocalDateTime;

public record DeadOutboxEventResponse(
        long id, String eventType, String aggregateType, long aggregateId, String status,
        int retryCount, LocalDateTime occurredAt, String lastErrorCode, String lastErrorMessage,
        int replayCount, Long replayOfEventId, LocalDateTime processedAt, long version) {
}
