package com.obdeadsoup.devpilot.outbox.application;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.function.ToDoubleFunction;
import org.springframework.stereotype.Component;

/** Outbox Gauge 只读内存快照；count 表示数量，oldest age 表示事件已等待多久。 */
@Component
public class OutboxBacklogMetricsBinder {
    public OutboxBacklogMetricsBinder(MeterRegistry registry, OutboxBacklogSnapshotService service) {
        gauge(registry, service, "pending", s -> s.current().pending());
        gauge(registry, service, "retry_wait_due", s -> s.current().retryDue());
        gauge(registry, service, "processing", s -> s.current().processing());
        gauge(registry, service, "open_dead", s -> s.current().openDead());
        Gauge.builder("devpilot.outbox.oldest.ready.age", service,
                        s -> s.current().oldestReadyAgeSeconds()).description("最老 READY Outbox 事件等待秒数")
                .baseUnit("seconds").register(registry);
        Gauge.builder("devpilot.outbox.stale.processing", service,
                        s -> s.current().staleProcessing()).description("超时 PROCESSING Outbox 数").register(registry);
        Gauge.builder("devpilot.outbox.backlog.snapshot.age", service,
                        OutboxBacklogSnapshotService::snapshotAgeSeconds).description("Outbox backlog 快照年龄秒数")
                .baseUnit("seconds").register(registry);
        Gauge.builder("devpilot.outbox.backlog.snapshot.stale", service,
                        OutboxBacklogSnapshotService::stale).description("Outbox backlog 快照是否陈旧").register(registry);
    }

    private void gauge(MeterRegistry registry, OutboxBacklogSnapshotService service, String status,
                       ToDoubleFunction<OutboxBacklogSnapshotService> value) {
        Gauge.builder("devpilot.outbox.backlog", service, value)
                .description("Outbox 当前 backlog 数").tag("status", status).register(registry);
    }
}
