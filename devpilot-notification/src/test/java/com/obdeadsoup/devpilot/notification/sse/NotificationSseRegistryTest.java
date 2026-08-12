package com.obdeadsoup.devpilot.notification.sse;

import static org.assertj.core.api.Assertions.assertThat;

import com.obdeadsoup.devpilot.notification.config.NotificationSseProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import com.obdeadsoup.devpilot.notification.application.NotificationMetrics;
import java.time.Duration;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

class NotificationSseRegistryTest {

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    private final NotificationSseRegistry registry = new NotificationSseRegistry(
            new NotificationSseProperties(true, Duration.ofMinutes(30), Duration.ofSeconds(20), 2),
            meters, new NotificationMetrics(meters));

    @AfterEach
    void close() {
        registry.closeAll();
    }

    @Test
    void supportsMultipleUsersAndEvictsOldestConnectionAtPerUserLimit() {
        SseEmitter first = registry.register(10);
        registry.register(10);
        registry.register(20);
        SseEmitter replacement = registry.register(10);

        assertThat(first).isNotSameAs(replacement);
        assertThat(registry.activeConnections(10)).isEqualTo(2);
        assertThat(registry.activeConnections(20)).isEqualTo(1);
        assertThat(registry.activeConnections()).isEqualTo(3);
        assertThat(meters.get("devpilot.notification.sse.connections").gauge().value()).isEqualTo(3);
    }

    @Test
    void closeAllCleansEveryUserConnection() {
        registry.register(10);
        registry.register(20);
        registry.closeAll();
        assertThat(registry.activeConnections()).isZero();
    }
}
