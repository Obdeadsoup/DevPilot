package com.obdeadsoup.devpilot.github.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅负责按配置唤醒 GitHub backlog refresh；测试可关闭而仍可直接测试 SnapshotService。 */
@Component
@ConditionalOnProperty(prefix = "devpilot.observability.backlog", name = "enabled", havingValue = "true", matchIfMissing = true)
public class GitHubBacklogRefreshScheduler {
    private final GitHubBacklogSnapshotService snapshots;
    public GitHubBacklogRefreshScheduler(GitHubBacklogSnapshotService snapshots) { this.snapshots = snapshots; }

    @Scheduled(fixedDelayString = "${devpilot.observability.backlog.refresh-interval:30s}")
    public void refresh() { snapshots.refresh(); }
}
