package com.obdeadsoup.devpilot.github.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 周期触发 Delivery 崩溃恢复扫描；只负责调度，候选筛选与状态迁移由 Recovery Service 完成。
 */
@Component
@ConditionalOnProperty(
        prefix = "devpilot.github",
        name = "delivery-recovery-enabled",
        havingValue = "true",
        matchIfMissing = true
)
public class GitHubDeliveryRecoveryScheduler {

    private final GitHubDeliveryRecoveryService recoveryService;

    public GitHubDeliveryRecoveryScheduler(GitHubDeliveryRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(fixedDelayString = "${devpilot.github.delivery-recovery-scan-interval:10s}")
    /** 按配置间隔触发一次有界批次恢复，不在 Scheduler 中持有业务事务。 */
    public void recover() {
        recoveryService.recover();
    }
}
