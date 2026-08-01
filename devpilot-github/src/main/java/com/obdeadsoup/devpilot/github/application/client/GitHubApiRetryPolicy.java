package com.obdeadsoup.devpilot.github.application.client;

import com.obdeadsoup.devpilot.github.config.GitHubIntegrationProperties;
import org.springframework.http.HttpMethod;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Set;
import java.util.function.DoubleSupplier;

/**
 * GitHub 读取请求的有限 Retry Policy。
 *
 * <p>只重试 GET/HEAD 的 NETWORK_ERROR、临时 5xx 和明确的 RATE_LIMITED；等待时间优先使用
 * Retry-After，其次 X-RateLimit-Reset，最后才使用指数 Backoff + jitter。长时间限流不占用同步请求线程。</p>
 */
public final class GitHubApiRetryPolicy {

    private static final Set<GitHubApiFailureType> RETRYABLE_FAILURES = Set.of(
            GitHubApiFailureType.NETWORK_ERROR,
            GitHubApiFailureType.TRANSIENT_SERVER_ERROR,
            GitHubApiFailureType.RATE_LIMITED
    );

    private final int maxReadAttempts;
    private final Duration initialBackoff;
    private final Duration maxBackoff;
    private final Duration maxSynchronousRateLimitWait;
    private final Clock clock;
    private final DoubleSupplier jitterSource;

    public GitHubApiRetryPolicy(
            GitHubIntegrationProperties properties,
            Clock clock,
            DoubleSupplier jitterSource
    ) {
        this.maxReadAttempts = properties.maxReadAttempts();
        this.initialBackoff = properties.initialBackoff();
        this.maxBackoff = properties.maxBackoff();
        this.maxSynchronousRateLimitWait = properties.maxSynchronousRateLimitWait();
        this.clock = clock;
        this.jitterSource = jitterSource;
    }

    /**
     * 判断当前失败是否还允许在调用线程中继续一次读取尝试。
     *
     * @param method HTTP 方法；写方法永远不会自动 Retry
     * @param attempt 已失败的本次尝试序号，从 1 开始
     * @param failure 经过 Error Decoder 分类的安全异常
     * @return 包含是否重试、等待时间及 retryAt 的不可变决策
     */
    public Decision decide(HttpMethod method, int attempt, GitHubApiException failure) {
        if (attempt >= maxReadAttempts
                || !(HttpMethod.GET.equals(method) || HttpMethod.HEAD.equals(method))
                || !failure.retryable()
                || !RETRYABLE_FAILURES.contains(failure.failureType())) {
            return Decision.stop(failure.retryAt());
        }

        Duration delay = rateLimitDelay(failure);
        if (delay == null) {
            delay = exponentialBackoff(attempt);
        }
        if (failure.failureType() == GitHubApiFailureType.RATE_LIMITED
                && delay.compareTo(maxSynchronousRateLimitWait) > 0) {
            // 长等待交给未来后台同步状态机，避免把当前 Web 请求线程挂到 Rate Limit Reset。
            return Decision.stop(clock.instant().plus(delay));
        }
        return new Decision(true, delay, clock.instant().plus(delay));
    }

    private Duration rateLimitDelay(GitHubApiException failure) {
        if (failure.failureType() != GitHubApiFailureType.RATE_LIMITED
                || failure.rateLimit() == null) {
            return null;
        }
        if (failure.rateLimit().retryAfter() != null) {
            return failure.rateLimit().retryAfter();
        }
        if (failure.rateLimit().resetAt() != null) {
            Duration delay = Duration.between(clock.instant(), failure.rateLimit().resetAt());
            return delay.isNegative() ? Duration.ZERO : delay;
        }
        return null;
    }

    private Duration exponentialBackoff(int attempt) {
        Duration base = initialBackoff;
        for (int index = 1; index < attempt && base.compareTo(maxBackoff) < 0; index++) {
            try {
                base = base.multipliedBy(2);
            } catch (ArithmeticException exception) {
                base = maxBackoff;
            }
            if (base.compareTo(maxBackoff) > 0) {
                base = maxBackoff;
            }
        }
        long minimumNanos = base.dividedBy(2).toNanos();
        long maximumNanos = min(maxBackoff, base.plus(base.dividedBy(2))).toNanos();
        double sample = Math.max(0.0, Math.min(Math.nextDown(1.0), jitterSource.getAsDouble()));
        long jitteredNanos = minimumNanos
                + (long) ((maximumNanos - minimumNanos) * sample);
        return Duration.ofNanos(Math.max(1L, jitteredNanos));
    }

    private Duration min(Duration first, Duration second) {
        return first.compareTo(second) <= 0 ? first : second;
    }

    public record Decision(boolean retry, Duration delay, Instant retryAt) {

        private static Decision stop(Instant retryAt) {
            return new Decision(false, Duration.ZERO, retryAt);
        }
    }
}
