package com.obdeadsoup.devpilot.notification.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import java.util.Locale;
import java.time.Duration;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;

/** Notification 持久化与 SSE Channel 分离计量的低基数指标门面。 */
@Component
public class NotificationMetrics {

    private final MeterRegistry registry;

    public NotificationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void created(String notificationType, boolean deduplicated) {
        Counter.builder(deduplicated ? "devpilot.notification.deduplicated" : "devpilot.notification.created")
                .description(deduplicated ? "Notification 去重累计次数" : "Notification 创建累计次数")
                .tag("notification_type", bounded(notificationType)).register(registry).increment();
    }

    public void sseSend(String channel, boolean success) {
        Counter.builder("devpilot.notification.sse.send").description("SSE Channel 发送累计次数")
                .tags("channel", channel, "result", success ? "success" : "failed")
                .register(registry).increment();
    }

    public void handler(String eventType, String result, Duration duration) {
        Timer.builder("devpilot.notification.outbox.handler")
                .description("Notification Outbox Handler 处理耗时")
                .tags("event_type", eventType.toLowerCase(Locale.ROOT), "result", result)
                .register(registry).record(duration);
    }

    private String bounded(String value) {
        if (value == null) {
            return "other";
        }
        try {
            return com.obdeadsoup.devpilot.notification.domain.NotificationType.valueOf(value)
                    .name().toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "other";
        }
    }
}
