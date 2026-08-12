package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.config.GitHubBacklogProperties;
import com.obdeadsoup.devpilot.github.config.GitHubIntegrationProperties;
import com.obdeadsoup.devpilot.github.config.GitHubReconciliationProperties;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubBacklogMapper;
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
 * 周期性执行两个有界聚合 SQL 并原子替换内存快照；刷新失败保留最后成功值，Gauge 永不触发 SQL。
 */
@Service
public class GitHubBacklogSnapshotService {

    private static final Logger log = LoggerFactory.getLogger(GitHubBacklogSnapshotService.class);
    private final AtomicReference<GitHubBacklogSnapshot> snapshot =
            new AtomicReference<>(GitHubBacklogSnapshot.empty());
    private final GitHubBacklogMapper mapper;
    private final GitHubIntegrationProperties deliveryProperties;
    private final GitHubReconciliationProperties syncProperties;
    private final GitHubBacklogProperties backlogProperties;
    private final MeterRegistry metrics;
    private final Clock clock;

    public GitHubBacklogSnapshotService(
            GitHubBacklogMapper mapper,
            GitHubIntegrationProperties deliveryProperties,
            GitHubReconciliationProperties syncProperties,
            GitHubBacklogProperties backlogProperties,
            MeterRegistry metrics,
            Clock clock) {
        this.mapper = mapper;
        this.deliveryProperties = deliveryProperties;
        this.syncProperties = syncProperties;
        this.backlogProperties = backlogProperties;
        this.metrics = metrics;
        this.clock = clock;
    }

    public void refresh() {
        Instant now = clock.instant();
        LocalDateTime utcNow = LocalDateTime.ofInstant(now, ZoneOffset.UTC);
        try {
            var delivery = mapper.delivery(utcNow, utcNow.minus(deliveryProperties.deliveryProcessingTimeout()));
            var sync = mapper.sync(utcNow, utcNow.minus(syncProperties.runTimeout()));
            snapshot.set(new GitHubBacklogSnapshot(
                    delivery.getReceivedCount(), delivery.getRetryWaitDueCount(), delivery.getProcessingCount(),
                    delivery.getStaleProcessingCount(), delivery.getOpenDeadCount(),
                    age(delivery.getOldestReadyAt(), now), sync.getPendingCount(), sync.getRetryWaitDueCount(),
                    sync.getRunningCount(), sync.getStaleRunningCount(), sync.getOpenDeadCount(),
                    age(sync.getOldestReadyAt(), now), age(sync.getOldestRunningAt(), now), now));
        } catch (RuntimeException exception) {
            Counter.builder("devpilot.github.backlog.refresh.failures")
                    .description("GitHub backlog 快照刷新失败累计次数").register(metrics).increment();
            log.warn("GitHub backlog snapshot refresh failed failureType={}",
                    exception.getClass().getSimpleName());
        }
    }

    public GitHubBacklogSnapshot current() { return snapshot.get(); }

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
