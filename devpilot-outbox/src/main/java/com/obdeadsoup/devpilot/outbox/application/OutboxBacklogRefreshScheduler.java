package com.obdeadsoup.devpilot.outbox.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 可关闭的 Outbox backlog 快照触发器；durable 处理本身仍由原 Recovery Scheduler 负责。 */
@Component
@ConditionalOnProperty(prefix = "devpilot.observability.backlog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class OutboxBacklogRefreshScheduler {
    private final OutboxBacklogSnapshotService snapshots;
    public OutboxBacklogRefreshScheduler(OutboxBacklogSnapshotService snapshots) { this.snapshots = snapshots; }
    @Scheduled(fixedDelayString = "${devpilot.observability.backlog.refresh-interval:30s}")
    public void refresh() { snapshots.refresh(); }
}
