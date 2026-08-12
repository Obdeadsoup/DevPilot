package com.obdeadsoup.devpilot.github.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/** 注册只读取 immutable 内存快照的 Gauge；数据库异常不会传播到 Prometheus scrape。 */
@Component
public class GitHubBacklogMetricsBinder {
    public GitHubBacklogMetricsBinder(MeterRegistry registry, GitHubBacklogSnapshotService service) {
        gauge(registry, service, "devpilot.github.delivery.backlog", "received", s -> s.current().deliveryReceived());
        gauge(registry, service, "devpilot.github.delivery.backlog", "retry_wait_due", s -> s.current().deliveryRetryDue());
        gauge(registry, service, "devpilot.github.delivery.backlog", "processing", s -> s.current().deliveryProcessing());
        gauge(registry, service, "devpilot.github.delivery.backlog", "open_dead", s -> s.current().deliveryOpenDead());
        Gauge.builder("devpilot.github.delivery.oldest.ready.age", service,
                        s -> s.current().deliveryOldestReadyAgeSeconds()).description("最老 READY Delivery 等待秒数")
                .baseUnit("seconds").register(registry);
        Gauge.builder("devpilot.github.delivery.stale.processing", service,
                        s -> s.current().deliveryStaleProcessing()).description("超时 PROCESSING Delivery 数").register(registry);
        gauge(registry, service, "devpilot.github.sync.backlog", "pending", s -> s.current().syncPending());
        gauge(registry, service, "devpilot.github.sync.backlog", "retry_wait_due", s -> s.current().syncRetryDue());
        gauge(registry, service, "devpilot.github.sync.backlog", "running", s -> s.current().syncRunning());
        gauge(registry, service, "devpilot.github.sync.backlog", "open_dead", s -> s.current().syncOpenDead());
        Gauge.builder("devpilot.github.sync.oldest.ready.age", service,
                        s -> s.current().syncOldestReadyAgeSeconds()).description("最老 READY Sync Run 等待秒数")
                .baseUnit("seconds").register(registry);
        Gauge.builder("devpilot.github.sync.oldest.running.age", service,
                        s -> s.current().syncOldestRunningAgeSeconds()).description("最老 RUNNING Sync Run 年龄秒数")
                .baseUnit("seconds").register(registry);
        Gauge.builder("devpilot.github.sync.stale.running", service,
                        s -> s.current().syncStaleRunning()).description("超时 RUNNING Sync Run 数").register(registry);
        Gauge.builder("devpilot.github.backlog.snapshot.age", service,
                        GitHubBacklogSnapshotService::snapshotAgeSeconds).description("GitHub backlog 快照年龄秒数")
                .baseUnit("seconds").register(registry);
        Gauge.builder("devpilot.github.backlog.snapshot.stale", service,
                        GitHubBacklogSnapshotService::stale).description("GitHub backlog 快照是否陈旧").register(registry);
    }

    private void gauge(MeterRegistry registry, GitHubBacklogSnapshotService service, String name, String status,
                       ToDoubleFunction<GitHubBacklogSnapshotService> value) {
        Gauge.builder(name, service, value).description("GitHub 当前 backlog 数").tag("status", status).register(registry);
    }
}
