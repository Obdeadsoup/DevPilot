package com.obdeadsoup.devpilot.outbox.domain;

import com.fasterxml.jackson.databind.JsonNode;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Outbox 与业务模块之间的中立事件信封。Payload 是显式 DTO 转换出的 JSON Tree，
 * 不携带 Java 类名或 Default Typing 元数据。
 */
public record OutboxEventEnvelope(
        String eventKey,
        String aggregateType,
        long aggregateId,
        String eventType,
        int schemaVersion,
        JsonNode payload,
        LocalDateTime occurredAt) {

    public OutboxEventEnvelope {
        Objects.requireNonNull(eventKey, "eventKey must not be null");
        Objects.requireNonNull(aggregateType, "aggregateType must not be null");
        Objects.requireNonNull(eventType, "eventType must not be null");
        Objects.requireNonNull(payload, "payload must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
        if (eventKey.isBlank() || eventKey.length() > 255
                || aggregateType.isBlank() || aggregateType.length() > 50
                || aggregateId <= 0
                || eventType.isBlank() || eventType.length() > 100
                || schemaVersion <= 0) {
            throw new IllegalArgumentException("invalid outbox event envelope");
        }
    }
}
