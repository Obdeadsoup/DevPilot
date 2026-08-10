package com.obdeadsoup.devpilot.audit.persistence.entity;

public record OutboxReplaySource(
        long id, String eventType, String aggregateType, long aggregateId, int schemaVersion,
        String payloadJson, String status, int retryCount, long version) {
}
