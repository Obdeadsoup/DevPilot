package com.obdeadsoup.devpilot.notification.sse;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Heartbeat 只维持并清理连接，不落库、不创建 Notification，也不经过 Outbox。 */
@Component
@ConditionalOnProperty(prefix = "devpilot.notification.sse", name = "enabled", havingValue = "true")
public class NotificationSseHeartbeatScheduler {

    private final NotificationSseRegistry registry;

    public NotificationSseHeartbeatScheduler(NotificationSseRegistry registry) {
        this.registry = registry;
    }

    @Scheduled(fixedDelayString = "${devpilot.notification.sse.heartbeat-interval:20s}")
    public void heartbeat() {
        registry.heartbeat();
    }
}
