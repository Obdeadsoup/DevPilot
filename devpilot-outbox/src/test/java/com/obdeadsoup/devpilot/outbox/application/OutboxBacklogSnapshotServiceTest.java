package com.obdeadsoup.devpilot.outbox.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.obdeadsoup.devpilot.outbox.config.OutboxBacklogProperties;
import com.obdeadsoup.devpilot.outbox.config.OutboxProperties;
import com.obdeadsoup.devpilot.outbox.persistence.entity.OutboxBacklogQuery;
import com.obdeadsoup.devpilot.outbox.persistence.mapper.OutboxBacklogMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class OutboxBacklogSnapshotServiceTest {
    @Test
    void refreshPublishesLastGoodAndEmptyAgeIsZero() {
        OutboxBacklogMapper mapper = mock(OutboxBacklogMapper.class);
        OutboxBacklogQuery query = new OutboxBacklogQuery();
        query.setPendingCount(4); query.setRetryWaitDueCount(2); query.setOpenDeadCount(1);
        when(mapper.snapshot(any(), any())).thenReturn(query);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        OutboxBacklogSnapshotService service = new OutboxBacklogSnapshotService(
                mapper, properties(), new OutboxBacklogProperties(false, Duration.ofSeconds(30), Duration.ofMinutes(2)),
                registry, Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC));
        new OutboxBacklogMetricsBinder(registry, service);

        service.refresh();
        assertThat(service.current().pending()).isEqualTo(4);
        assertThat(service.current().oldestReadyAgeSeconds()).isZero();
        assertThat(registry.get("devpilot.outbox.backlog").tag("status", "retry_wait_due").gauge().value())
                .isEqualTo(2);
        when(mapper.snapshot(any(), any())).thenThrow(new IllegalStateException("db down"));
        service.refresh();
        assertThat(service.current().pending()).isEqualTo(4);
    }

    private OutboxProperties properties() {
        return new OutboxProperties(false, Duration.ofSeconds(5), 100, 5, Duration.ofSeconds(1),
                Duration.ofMinutes(5), Duration.ofMinutes(2), 2, 4, 200);
    }
}
