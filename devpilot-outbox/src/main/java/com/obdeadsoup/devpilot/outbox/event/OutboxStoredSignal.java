package com.obdeadsoup.devpilot.outbox.event;

/** 提交后的低延迟唤醒只携带数据库 ID；可靠来源始终是 dp_outbox_event。 */
public record OutboxStoredSignal(long outboxId) {
}
