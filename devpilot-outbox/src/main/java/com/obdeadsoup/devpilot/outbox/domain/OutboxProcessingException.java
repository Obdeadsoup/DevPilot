package com.obdeadsoup.devpilot.outbox.domain;

/** 可安全落库的 Outbox 处理异常，只包含稳定分类和有界消息，不携带 Payload 或 SQL。 */
public class OutboxProcessingException extends RuntimeException {

    private final OutboxFailureType failureType;

    public OutboxProcessingException(OutboxFailureType failureType, String message) {
        super(message);
        this.failureType = failureType;
    }

    public OutboxFailureType failureType() {
        return failureType;
    }
}
