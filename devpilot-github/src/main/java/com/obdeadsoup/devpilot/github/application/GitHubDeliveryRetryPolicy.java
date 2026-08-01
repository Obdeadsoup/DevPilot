package com.obdeadsoup.devpilot.github.application;

import java.time.Duration;
import java.util.Objects;

/**
 * Webhook Delivery 状态机的有限指数退避策略。
 * 重试次数达到上限后由 State Service 转入 DEAD，避免无限占用恢复扫描资源。
 */
public class GitHubDeliveryRetryPolicy {

    private final int maxRetries;
    private final Duration initialDelay;
    private final Duration maxDelay;

    public GitHubDeliveryRetryPolicy(int maxRetries, Duration initialDelay, Duration maxDelay) {
        if (maxRetries < 0) {
            throw new IllegalArgumentException("maxRetries must not be negative");
        }
        this.maxRetries = maxRetries;
        this.initialDelay = requirePositive(initialDelay, "initialDelay");
        this.maxDelay = Objects.requireNonNull(maxDelay, "maxDelay must not be null");
        if (maxDelay.compareTo(initialDelay) < 0) {
            throw new IllegalArgumentException("maxDelay must be greater than or equal to initialDelay");
        }
    }

    /** 判断本次失败后是否仍可进入 RETRY_WAIT。 */
    public boolean shouldRetryAfterFailure(int currentRetryCount) {
        return currentRetryCount >= 0 && currentRetryCount < maxRetries;
    }

    /**
     * 计算失败后的退避时间；首次失败使用 initialDelay，随后倍增且不超过 maxDelay。
     */
    public Duration retryDelay(int retryCountAfterFailure) {
        if (retryCountAfterFailure <= 0) {
            throw new IllegalArgumentException("retryCountAfterFailure must be positive");
        }
        Duration delay = initialDelay;
        int remainingDoublings = retryCountAfterFailure - 1;
        while (remainingDoublings > 0 && delay.compareTo(maxDelay) < 0) {
            if (delay.compareTo(maxDelay.dividedBy(2)) > 0) {
                return maxDelay;
            }
            try {
                delay = delay.multipliedBy(2);
            } catch (ArithmeticException exception) {
                return maxDelay;
            }
            remainingDoublings--;
        }
        return delay.compareTo(maxDelay) > 0 ? maxDelay : delay;
    }

    private Duration requirePositive(Duration duration, String name) {
        Objects.requireNonNull(duration, name + " must not be null");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(name + " must be positive");
        }
        return duration;
    }
}
