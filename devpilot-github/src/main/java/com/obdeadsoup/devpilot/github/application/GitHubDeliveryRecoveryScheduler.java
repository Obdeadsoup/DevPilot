package com.obdeadsoup.devpilot.github.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

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
    public void recover() {
        recoveryService.recover();
    }
}
