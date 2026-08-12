package com.obdeadsoup.devpilot.github.application;

import static org.assertj.core.api.Assertions.assertThat;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class GitHubObservabilityMetricsTest {
    @Test
    void deliveryAndSyncMetersUseOnlyBoundedTags() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        new GitHubDeliveryMetrics(registry).processing("attacker-event", "dead", Duration.ofMillis(10));
        GitHubSyncMetrics sync = new GitHubSyncMetrics(registry);
        sync.completed(new GitHubSyncRunEntity(99, 88, "COMMIT", "SCHEDULED", "RUNNING", 1,
                null, LocalDateTime.now().minusSeconds(2), null, null, null, null,
                LocalDateTime.now(), LocalDateTime.now(), 1), "success", LocalDateTime.now());

        assertThat(registry.get("devpilot.github.delivery.processing").tag("event_type", "other").timer().count())
                .isEqualTo(1);
        assertThat(registry.get("devpilot.github.sync.run.duration").tag("resource_type", "commit").timer().count())
                .isEqualTo(1);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).extracting(io.micrometer.core.instrument.Tag::getKey).doesNotContain(
                        "repositoryId", "bindingId", "deliveryId", "runId", "correlationId"));
    }
}
