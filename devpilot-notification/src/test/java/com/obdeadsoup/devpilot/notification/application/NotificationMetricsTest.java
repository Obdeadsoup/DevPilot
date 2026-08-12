package com.obdeadsoup.devpilot.notification.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class NotificationMetricsTest {
    @Test
    void separatesCreatedDeduplicatedHandlerAndSseFailure() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        NotificationMetrics metrics = new NotificationMetrics(registry);
        metrics.created("TASK_ASSIGNED", false);
        metrics.created("TASK_ASSIGNED", true);
        metrics.handler("TASK_ASSIGNED_V1", "success", Duration.ofMillis(3));
        metrics.sseSend("notification", false);

        assertThat(registry.get("devpilot.notification.created").counter().count()).isEqualTo(1);
        assertThat(registry.get("devpilot.notification.deduplicated").counter().count()).isEqualTo(1);
        assertThat(registry.get("devpilot.notification.outbox.handler").timer().count()).isEqualTo(1);
        assertThat(registry.get("devpilot.notification.sse.send").tag("result", "failed").counter().count())
                .isEqualTo(1);
    }
}
