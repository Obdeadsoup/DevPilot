package com.obdeadsoup.devpilot.outbox.application;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** fixedDelay 触发数据库恢复扫描；它不持有业务 Payload，也不提前 claim。 */
@Component
@ConditionalOnProperty(prefix = "devpilot.outbox", name = "enabled", havingValue = "true")
public class OutboxRecoveryScheduler {

    private final OutboxRecoveryService recoveryService;

    public OutboxRecoveryScheduler(OutboxRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @Scheduled(fixedDelayString = "${devpilot.outbox.scan-interval:5s}")
    public void recover() {
        recoveryService.recover();
    }
}
