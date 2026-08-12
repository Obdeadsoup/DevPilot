package com.obdeadsoup.devpilot.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;

class OutboxObservabilityMetricsTest {
    @Test
    void recordsProcessedDeadAndDurationWithoutEntityIds() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxMetrics metrics = new OutboxMetrics(registry);
        metrics.processed("TASK_ASSIGNED_V1");
        metrics.failed("TASK_ASSIGNED_V1", "HANDLER_FAILURE", true);
        metrics.processing("TASK_ASSIGNED_V1", "processed", Duration.ofMillis(5));

        assertThat(registry.get("devpilot.outbox.processed").counter().count()).isEqualTo(1);
        assertThat(registry.get("devpilot.outbox.dead.transitions").counter().count()).isEqualTo(1);
        assertThat(registry.get("devpilot.outbox.processing").timer().count()).isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                        .doesNotContain("outboxId", "eventKey", "taskId"));
    }
}
