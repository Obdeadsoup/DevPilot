package com.obdeadsoup.devpilot.outbox.domain;

/**
 * 一个 Handler 只接受一个白名单 eventType + schemaVersion。实现必须完成可幂等持久化副作用，
 * Worker 会在同一事务中把 Outbox 标记为 PROCESSED。
 */
public interface OutboxEventHandler {

    String supportedEventType();

    int supportedSchemaVersion();

    OutboxHandleResult handle(OutboxEventEnvelope event);
}
