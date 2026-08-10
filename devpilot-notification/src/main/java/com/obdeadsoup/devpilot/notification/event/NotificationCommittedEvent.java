package com.obdeadsoup.devpilot.notification.event;

import java.time.LocalDateTime;

/** Notification 新行提交后的最小 Channel 信号；不包含正文、Dedupe Key 或 Outbox Payload。 */
public record NotificationCommittedEvent(
        long notificationId, long recipientUserId, LocalDateTime occurredAt) {
}
