package com.obdeadsoup.devpilot.outbox.application;

import com.obdeadsoup.devpilot.outbox.config.OutboxBacklogProperties;
import com.obdeadsoup.devpilot.outbox.config.OutboxProperties;
import com.obdeadsoup.devpilot.outbox.persistence.mapper.OutboxBacklogMapper;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * 定时刷新 Outbox 聚合快照；刷新失败保留 last good，避免一次数据库抖动让 Gauge scrape 抛错或归零。
 */
@Service
public class OutboxBacklogSnapshotService {
    private static final Logger log = LoggerFactory.getLogger(OutboxBacklogSnapshotService.class);
    private final AtomicReference<OutboxBacklogSnapshot> snapshot =
            new AtomicReference<>(OutboxBacklogSnapshot.empty());
    private final OutboxBacklogMapper mapper;
    private final OutboxProperties outboxProperties;
    private final OutboxBacklogProperties backlogProperties;
    private final MeterRegistry metrics;
    private final Clock clock;

    public OutboxBacklogSnapshotService(
            OutboxBacklogMapper mapper, OutboxProperties outboxProperties,
            OutboxBacklogProperties backlogProperties, MeterRegistry metrics, Clock clock) {
        this.mapper = mapper;
        this.outboxProperties = outboxProperties;
        this.backlogProperties = backlogProperties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void refresh() {
        Instant now = clock.instant();
        LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        try {
            var value = mapper.snapshot(utcNow, utcNow.minus(outboxProperties.processingTimeout()));
            snapshot.set(new OutboxBacklogSnapshot(
                    value.getPendingCount(), value.getRetryWaitDueCount(), value.getProcessingCount(),
                    value.getStaleProcessingCount(), value.getOpenDeadCount(), age(value.getOldestReadyAt(), now), now));
        } catch (RuntimeException exception) {
            Counter.builder("devpilot.outbox.backlog.refresh.failures")
                    .description("Outbox backlog 快照刷新失败累计次数").register(metrics).increment();
            log.warn("Outbox backlog snapshot refresh failed failureType={}", exception.getClass().getSimpleName());
        }
    }

    public OutboxBacklogSnapshot current() { return snapshot.get(); }

    public double snapshotAgeSeconds() {
        Instant updated = current().lastUpdatedAt();
        return updated == null ? Double.POSITIVE_INFINITY
                : Math.max(0, Duration.between(updated, clock.instant()).toMillis() / 1000.0);
    }

    public double stale() {
        return snapshotAgeSeconds() > backlogProperties.staleAfter().toSeconds() ? 1 : 0;
    }

    private double age(LocalDateTime value, Instant now) {
        if (value == null) return 0;
        return Math.max(0, Duration.between(value.toInstant(ZoneOffset.UTC), now).toMillis() / 1000.0);
    }
}
