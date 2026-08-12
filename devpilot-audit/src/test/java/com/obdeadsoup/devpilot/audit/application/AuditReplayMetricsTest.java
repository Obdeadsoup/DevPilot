package com.obdeadsoup.devpilot.audit.application;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class AuditReplayMetricsTest {
    @Test
    void recordsOnlyResourceAndResult() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new AuditReplayMetrics(registry).record("outbox", "created");
        var meter = registry.get("devpilot.audit.replay").counter();
        assertThat(meter.count()).isEqualTo(1);
        assertThat(meter.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey)
                .containsExactlyInAnyOrder("resource_type", "result");
    }
}
