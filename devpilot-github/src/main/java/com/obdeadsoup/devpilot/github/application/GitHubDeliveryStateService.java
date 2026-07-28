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

@Service
public class GitHubDeliveryStateService {

    private final GitHubDeliveryMapper deliveryMapper;
    private final GitHubDeliveryRetryPolicy retryPolicy;
    private final Clock clock;

    public GitHubDeliveryStateService(
            GitHubDeliveryMapper deliveryMapper,
            GitHubDeliveryRetryPolicy retryPolicy,
            Clock clock
    ) {
        this.deliveryMapper = deliveryMapper;
        this.retryPolicy = retryPolicy;
        this.clock = clock;
    }

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
        return updated == 1 ? Optional.of(result) : Optional.empty();
    }

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
        return updated == 1 ? Optional.of(result) : Optional.empty();
    }
}
