package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.config.GitHubReconciliationProperties;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Sync Run 的有限指数退避策略。Rate Limit 优先使用 GitHub 给出的未来 retryAt；
 * 网络/5xx 在统一 HTTP Retry 耗尽后才进入本状态机，并受 maxRunAttempts 再次限界。
 */
@Component
public class GitHubSyncRetryPolicy {

    private final GitHubReconciliationProperties properties;

    public GitHubSyncRetryPolicy(GitHubReconciliationProperties properties) {
        this.properties = properties;
    }

    public boolean shouldRetry(boolean retryable, int attemptCount) {
        return retryable && attemptCount < properties.maxRunAttempts();
    }

    public Instant nextRetryAt(int attemptCount, Instant upstreamRetryAt, Instant now) {
        if (upstreamRetryAt != null && upstreamRetryAt.isAfter(now)) {
            return upstreamRetryAt;
        }
        Duration delay = properties.retryInitialDelay();
        int doublings = Math.max(0, attemptCount - 1);
        while (doublings-- > 0 && delay.compareTo(properties.retryMaxDelay()) < 0) {
            if (delay.compareTo(properties.retryMaxDelay().dividedBy(2)) > 0) {
                delay = properties.retryMaxDelay();
                break;
            }
            delay = delay.multipliedBy(2);
        }
        return now.plus(delay.compareTo(properties.retryMaxDelay()) > 0
                ? properties.retryMaxDelay()
                : delay);
    }
}
