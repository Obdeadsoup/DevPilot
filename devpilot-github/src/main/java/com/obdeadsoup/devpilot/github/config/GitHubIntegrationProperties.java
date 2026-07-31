package com.obdeadsoup.devpilot.github.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "devpilot.github")
public record GitHubIntegrationProperties(
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
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

    @AssertTrue(message = "connect timeout must be positive")
    public boolean isConnectTimeoutPositive() {
        return connectTimeout != null && !connectTimeout.isZero() && !connectTimeout.isNegative();
    }

    @AssertTrue(message = "read timeout must be positive")
    public boolean isReadTimeoutPositive() {
        return readTimeout != null && !readTimeout.isZero() && !readTimeout.isNegative();
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
