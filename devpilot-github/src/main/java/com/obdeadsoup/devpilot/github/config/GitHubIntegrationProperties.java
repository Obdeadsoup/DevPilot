package com.obdeadsoup.devpilot.github.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

/**
 * GitHub 集成的统一配置入口，同时承载 API Client 与 Webhook Delivery 的受约束参数。
 *
 * <p>生产环境的 API Host 还会由 {@link GitHubRestClientConfiguration} 做第二次校验；
 * loopback 地址只为测试 Profile 的本地 Mock Server 保留。</p>
 */
@Validated
@ConfigurationProperties(prefix = "devpilot.github")
public record GitHubIntegrationProperties(
        @NotNull URI baseUrl,
        @NotBlank String apiVersion,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) @Max(5) int maxReadAttempts,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff,
        @NotNull Duration maxSynchronousRateLimitWait,
        @Min(1) @Max(32) int maxConcurrentRequestsPerCredential,
        @NotNull Duration concurrencyAcquireTimeout,
        @NotBlank String userAgent,
        @Min(1) @Max(16) int workerCoreThreads,
        @Min(1) @Max(32) int workerMaxThreads,
        @Min(1) @Max(10_000) int workerQueueCapacity,
        @Min(1_024) @Max(10_485_760) int webhookMaxPayloadBytes,
        @Min(0) @Max(20) int deliveryMaxRetries,
        @NotNull Duration deliveryRetryInitialDelay,
        @NotNull Duration deliveryRetryMaxDelay,
        @NotNull Duration deliveryRecoveryScanInterval,
        @Min(1) @Max(1_000) int deliveryRecoveryBatchSize,
        @NotNull Duration deliveryProcessingTimeout,
        boolean deliveryRecoveryEnabled
) {

    @AssertTrue(message = "base URL must be the public GitHub API or a loopback test endpoint")
    public boolean isBaseUrlStructurallySafe() {
        if (baseUrl == null
                || baseUrl.getUserInfo() != null
                || baseUrl.getQuery() != null
                || baseUrl.getFragment() != null
                || (baseUrl.getPath() != null && !baseUrl.getPath().isBlank()
                && !"/".equals(baseUrl.getPath()))) {
            return false;
        }
        boolean publicGitHub = "https".equalsIgnoreCase(baseUrl.getScheme())
                && "api.github.com".equalsIgnoreCase(baseUrl.getHost())
                && (baseUrl.getPort() == -1 || baseUrl.getPort() == 443);
        boolean loopbackTestEndpoint = ("http".equalsIgnoreCase(baseUrl.getScheme())
                || "https".equalsIgnoreCase(baseUrl.getScheme()))
                && ("localhost".equalsIgnoreCase(baseUrl.getHost())
                || "127.0.0.1".equals(baseUrl.getHost())
                || "[::1]".equals(baseUrl.getHost())
                || "::1".equals(baseUrl.getHost()));
        return publicGitHub || loopbackTestEndpoint;
    }

    @AssertTrue(message = "connect timeout must be positive")
    public boolean isConnectTimeoutPositive() {
        return connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative();
    }

    @AssertTrue(message = "read timeout must be positive")
    public boolean isReadTimeoutPositive() {
        return readTimeout != null && !readTimeout.isZero() && !readTimeout.isNegative();
    }

    @AssertTrue(message = "initial API backoff must be positive")
    public boolean isInitialBackoffPositive() {
        return isPositive(initialBackoff);
    }

    @AssertTrue(message = "max API backoff must be greater than or equal to initial backoff")
    public boolean isMaxBackoffValid() {
        return initialBackoff != null
                && maxBackoff != null
                && maxBackoff.compareTo(initialBackoff) >= 0;
    }

    @AssertTrue(message = "max synchronous rate limit wait must be positive")
    public boolean isMaxSynchronousRateLimitWaitPositive() {
        return isPositive(maxSynchronousRateLimitWait);
    }

    @AssertTrue(message = "credential concurrency acquire timeout must be positive")
    public boolean isConcurrencyAcquireTimeoutPositive() {
        return isPositive(concurrencyAcquireTimeout);
    }

    @AssertTrue(message = "worker max threads must be greater than or equal to core threads")
    public boolean isWorkerPoolSizeValid() {
        return workerMaxThreads >= workerCoreThreads;
    }

    @AssertTrue(message = "delivery retry initial delay must be positive")
    public boolean isDeliveryRetryInitialDelayPositive() {
        return isPositive(deliveryRetryInitialDelay);
    }

    @AssertTrue(message = "delivery retry max delay must be greater than or equal to initial delay")
    public boolean isDeliveryRetryMaxDelayValid() {
        return deliveryRetryInitialDelay != null
                && deliveryRetryMaxDelay != null
                && deliveryRetryMaxDelay.compareTo(deliveryRetryInitialDelay) >= 0;
    }

    @AssertTrue(message = "delivery recovery scan interval must be positive")
    public boolean isDeliveryRecoveryScanIntervalPositive() {
        return isPositive(deliveryRecoveryScanInterval);
    }

    @AssertTrue(message = "delivery processing timeout must be positive")
    public boolean isDeliveryProcessingTimeoutPositive() {
        return isPositive(deliveryProcessingTimeout);
    }

    private boolean isPositive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
