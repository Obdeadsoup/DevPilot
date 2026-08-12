package com.obdeadsoup.devpilot.outbox.application;

import com.obdeadsoup.devpilot.outbox.domain.OutboxEventStatus;
import com.obdeadsoup.devpilot.outbox.domain.OutboxRetryPolicy;
import com.obdeadsoup.devpilot.outbox.persistence.entity.OutboxEventEntity;
import com.obdeadsoup.devpilot.outbox.persistence.mapper.OutboxEventMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * 以 status + version 条件 UPDATE 维护 Outbox 状态机。扫描结果不代表所有权，只有 claim 更新一行的 Worker
 * 才能处理；旧 Worker 的旧 version 不能覆盖恢复后的新状态。
 */
@Service
public class OutboxStateService {

    private final OutboxEventMapper mapper;
    private final OutboxRetryPolicy retryPolicy;
    private final Clock clock;
    private final OutboxMetrics metrics;

    public OutboxStateService(
            OutboxEventMapper mapper,
            OutboxRetryPolicy retryPolicy,
            Clock clock,
            OutboxMetrics metrics) {
        this.mapper = mapper;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** PENDING 或已到期 RETRY_WAIT 才能被抢占；成功后返回递增 version 的 PROCESSING 快照。 */
    @Transactional
    public Optional<OutboxEventEntity> claim(long outboxId) {
        OutboxEventEntity candidate = mapper.findById(outboxId).orElse(null);
        if (candidate == null) {
            return Optional.empty();
        }
        LocalDateTime now = LocalDateTime.now(clock);
        if (mapper.claim(outboxId, candidate.getVersion(), now) != 1) {
            return Optional.empty();
        }
        return mapper.findById(outboxId);
    }

    /** 必须加入 Handler 当前事务，使 Notification 副作用和 PROCESSED 同时提交或回滚。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public void markProcessed(OutboxEventEntity event) {
        if (mapper.markProcessed(event.getId(), event.getVersion(), LocalDateTime.now(clock)) != 1) {
            throw new IllegalStateException("Outbox processed state conflict");
        }
        metrics.processed(event.getEventType());
    }

    /** 处理事务回滚后，以独立事务安全记录 Retry/DEAD，避免错误状态随原异常一起回滚。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxEventStatus markFailed(OutboxEventEntity event, OutboxFailureDecision decision) {
        int failureCount = event.getRetryCount() + 1;
        boolean dead = !decision.retryable() || retryPolicy.exhausted(failureCount);
        OutboxEventStatus target = dead ? OutboxEventStatus.DEAD : OutboxEventStatus.RETRY_WAIT;
        LocalDateTime retryAt = dead
                ? null
                : retryPolicy.nextRetryAt(LocalDateTime.now(clock), failureCount);
        int updated = mapper.markFailure(
                event.getId(),
                event.getVersion(),
                target.name(),
                failureCount,
                retryAt,
                decision.errorCode(),
                bounded(decision.safeMessage()));
        if (updated == 1) {
            metrics.failed(event.getEventType(), decision.failureType().name(), dead);
        }
        return target;
    }

    /** 超时 PROCESSING 按同一重试额度恢复；cutoff + version 防止恢复器覆盖仍在有效处理的新 Worker。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public OutboxEventStatus recoverStale(OutboxEventEntity event, LocalDateTime cutoff) {
        int failureCount = event.getRetryCount() + 1;
        boolean dead = retryPolicy.exhausted(failureCount);
        OutboxEventStatus target = dead ? OutboxEventStatus.DEAD : OutboxEventStatus.RETRY_WAIT;
        LocalDateTime retryAt = dead
                ? null
                : retryPolicy.nextRetryAt(LocalDateTime.now(clock), failureCount);
        int updated = mapper.recoverStale(
                event.getId(), event.getVersion(), cutoff, target.name(), failureCount, retryAt);
        if (updated == 1) {
            metrics.failed(event.getEventType(), "PROCESSING_TIMEOUT", dead);
        }
        return target;
    }

    private String bounded(String message) {
        if (message == null || message.isBlank()) {
            return "Outbox processing failed";
        }
        return message.substring(0, Math.min(500, message.length()));
    }
}
