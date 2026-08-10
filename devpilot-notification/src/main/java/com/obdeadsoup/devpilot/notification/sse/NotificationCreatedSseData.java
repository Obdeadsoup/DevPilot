package com.obdeadsoup.devpilot.notification.sse;

import java.time.LocalDateTime;

public record NotificationCreatedSseData(
        long notificationId, long unreadCount, LocalDateTime occurredAt) {
}
