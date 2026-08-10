package com.obdeadsoup.devpilot.outbox.application;

import com.obdeadsoup.devpilot.outbox.event.OutboxStoredSignal;
import org.springframework.stereotype.Component;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * AFTER_COMMIT 仅降低正常路径延迟。Listener 丢失、JVM 崩溃或 Executor 拒绝时，PENDING 仍由数据库扫描恢复。
 */
@Component
@ConditionalOnProperty(prefix = "devpilot.outbox", name = "enabled", havingValue = "true")
public class OutboxStoredSignalListener {

    private final OutboxRecoveryService recoveryService;

    public OutboxStoredSignalListener(OutboxRecoveryService recoveryService) {
        this.recoveryService = recoveryService;
    }

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(OutboxStoredSignal signal) {
        recoveryService.submit(signal.outboxId());
    }
}
