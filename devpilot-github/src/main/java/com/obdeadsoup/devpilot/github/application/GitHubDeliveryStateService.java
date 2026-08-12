package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.GitHubDeliveryFailureClassifier.Classification;
import com.obdeadsoup.devpilot.github.domain.GitHubDeliveryStatus;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

/**
 * Delivery 状态机的事务边界，使用 version + 条件 UPDATE 完成抢占、Retry、DEAD 与超时恢复。
 */
@Service
public class GitHubDeliveryStateService {

    private final GitHubDeliveryMapper deliveryMapper;
    private final GitHubDeliveryRetryPolicy retryPolicy;
    private final Clock clock;
    private final GitHubDeliveryMetrics metrics;

    public GitHubDeliveryStateService(
            GitHubDeliveryMapper deliveryMapper,
            GitHubDeliveryRetryPolicy retryPolicy,
            Clock clock,
            GitHubDeliveryMetrics metrics
    ) {
        this.deliveryMapper = deliveryMapper;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
        this.metrics = metrics;
    }

    /** RECEIVED/到期 RETRY_WAIT 抢占为 PROCESSING；并发失败返回空。 */
    @Transactional
    public Optional<GitHubDeliveryEntity> claim(long deliveryId) {
        Optional<GitHubDeliveryEntity> candidate = deliveryMapper.findById(deliveryId);
        if (candidate.isEmpty()) {
            return Optional.empty();
        }
        GitHubDeliveryEntity delivery = candidate.get();
        LocalDateTime startedAt = LocalDateTime.now(clock);
        int claimed = deliveryMapper.claim(delivery.id(), delivery.version(), startedAt);
        if (claimed != 1) {
            return Optional.empty();
        }
        return deliveryMapper.findById(deliveryId);
    }

    /**
     * 在独立事务中记录处理失败，按分类与次数进入 RETRY_WAIT 或 DEAD。
     * 独立事务避免成功处理事务回滚时一并丢失失败状态。
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<GitHubDeliveryStatus> handleFailure(
            GitHubDeliveryEntity delivery,
            Classification failure
    ) {
        LocalDateTime failedAt = LocalDateTime.now(clock);
        int updated;
        GitHubDeliveryStatus result;
        if (failure.retryable() && retryPolicy.shouldRetryAfterFailure(delivery.retryCount())) {
            int retryCountAfterFailure = delivery.retryCount() + 1;
            LocalDateTime nextRetryAt = failedAt.plus(retryPolicy.retryDelay(retryCountAfterFailure));
            updated = deliveryMapper.markRetryWait(
                    delivery.id(),
                    delivery.version(),
                    nextRetryAt,
                    failure.stableErrorCode(),
                    failure.safeErrorMessage()
            );
            result = GitHubDeliveryStatus.RETRY_WAIT;
        } else {
            updated = deliveryMapper.markDead(
                    delivery.id(),
                    delivery.version(),
                    failure.stableErrorCode(),
                    failure.safeErrorMessage(),
                    failedAt
            );
            result = GitHubDeliveryStatus.DEAD;
        }
        if (updated == 1 && result == GitHubDeliveryStatus.DEAD) {
            metrics.transitionedToDead(delivery.eventType());
        }
        return updated == 1 ? Optional.of(result) : Optional.empty();
    }

    /** 将超时 PROCESSING 以 version 和 startedAt 截止条件恢复为 RETRY_WAIT 或 DEAD。 */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<GitHubDeliveryStatus> recoverStaleProcessing(
            GitHubDeliveryEntity delivery,
            LocalDateTime cutoff
    ) {
        LocalDateTime recoveredAt = LocalDateTime.now(clock);
        int updated;
        GitHubDeliveryStatus result;
        if (retryPolicy.shouldRetryAfterFailure(delivery.retryCount())) {
            int retryCountAfterFailure = delivery.retryCount() + 1;
            LocalDateTime nextRetryAt = recoveredAt.plus(retryPolicy.retryDelay(retryCountAfterFailure));
            updated = deliveryMapper.recoverStaleProcessingToRetryWait(
                    delivery.id(), delivery.version(), cutoff, nextRetryAt
            );
            result = GitHubDeliveryStatus.RETRY_WAIT;
        } else {
            updated = deliveryMapper.recoverStaleProcessingToDead(
                    delivery.id(), delivery.version(), cutoff, recoveredAt
            );
            result = GitHubDeliveryStatus.DEAD;
        }
        if (updated == 1 && result == GitHubDeliveryStatus.DEAD) {
            metrics.transitionedToDead(delivery.eventType());
        }
        return updated == 1 ? Optional.of(result) : Optional.empty();
    }
}
