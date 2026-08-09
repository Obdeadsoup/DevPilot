package com.obdeadsoup.devpilot.outbox.application;

import com.obdeadsoup.devpilot.outbox.domain.OutboxEventEnvelope;

/**
 * 在调用方当前事务中持久化事件。实现不得开启 REQUIRES_NEW；业务回滚必须同时撤销 Outbox INSERT。
 */
public interface OutboxEventPublisher {

    long publish(OutboxEventEnvelope event);
}
