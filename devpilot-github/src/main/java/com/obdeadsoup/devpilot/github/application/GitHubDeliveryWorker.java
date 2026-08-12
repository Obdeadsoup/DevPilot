package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.time.Duration;

/**
 * Delivery 异步 Worker：先抢占状态，再运行成功事务；失败分类和失败记录使用独立事务。
 */
@Component
public class GitHubDeliveryWorker {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubDeliveryWorker.class);

    private final GitHubDeliveryStateService stateService;
    private final GitHubDeliveryProcessingService processingService;
    private final GitHubDeliveryFailureClassifier failureClassifier;
    private final GitHubDeliveryMetrics metrics;

    public GitHubDeliveryWorker(
            GitHubDeliveryStateService stateService,
            GitHubDeliveryProcessingService processingService,
            GitHubDeliveryFailureClassifier failureClassifier,
            GitHubDeliveryMetrics metrics
    ) {
        this.stateService = stateService;
        this.processingService = processingService;
        this.failureClassifier = failureClassifier;
        this.metrics = metrics;
    }

    /**
     * 尝试处理指定 Delivery；抢占失败表示已被其他 Worker 处理，直接返回而非重复执行业务。
     */
    public void process(long deliveryId) {
        Optional<GitHubDeliveryEntity> claimed = stateService.claim(deliveryId);
        if (claimed.isEmpty()) {
            return;
        }
        long startedAt = System.nanoTime();
        try {
            processingService.process(claimed.get());
            metrics.processing(claimed.get().eventType(), "success",
                    Duration.ofNanos(System.nanoTime() - startedAt));
        } catch (RuntimeException exception) {
            GitHubDeliveryFailureClassifier.Classification failure = failureClassifier.classify(exception);
            String resultStatus = stateService.handleFailure(claimed.get(), failure)
                    .map(Enum::name)
                    .orElse("UNCHANGED");
            if (!"UNCHANGED".equals(resultStatus)) {
                metrics.processing(claimed.get().eventType(), resultStatus.toLowerCase(java.util.Locale.ROOT),
                        Duration.ofNanos(System.nanoTime() - startedAt));
            }
            LOGGER.warn(
                    "GitHub delivery processing failed deliveryId={} errorCode={} exceptionType={} resultStatus={}",
                    deliveryId,
                    failure.stableErrorCode(),
                    exception.getClass().getName(),
                    resultStatus
            );
        }
    }
}
