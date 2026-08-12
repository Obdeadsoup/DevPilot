package com.obdeadsoup.devpilot.config;

import static org.assertj.core.api.Assertions.assertThat;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

class ObservabilityConfigurationTest {
    @Test
    void rejectsBusinessEntityAndCorrelationTagsButKeepsBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        registry.config().meterFilter(new ObservabilityConfiguration().rejectHighCardinalityBusinessTags());
        registry.counter("devpilot.safe", "status", "success").increment();
        registry.counter("devpilot.unsafe", "correlationId", "request-123").increment();

        assertThat(registry.find("devpilot.safe").counter()).isNotNull();
        assertThat(registry.find("devpilot.unsafe").counter()).isNull();
    }
}
