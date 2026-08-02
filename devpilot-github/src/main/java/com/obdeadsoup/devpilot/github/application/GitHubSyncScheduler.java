package com.obdeadsoup.devpilot.github.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 薄 Scheduler：只触发发现与提交，状态读取、创建和 claim 均由可测试 Service 完成。 */
@Component
@ConditionalOnProperty(
        prefix = "devpilot.github.reconciliation",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class GitHubSyncScheduler {

    private final GitHubSyncRunService syncRunService;

    public GitHubSyncScheduler(GitHubSyncRunService syncRunService) {
        this.syncRunService = syncRunService;
    }

    @Scheduled(fixedDelayString = "${devpilot.github.reconciliation.scan-interval:5m}")
    public void scan() {
        syncRunService.discoverAndSubmit();
    }
}
