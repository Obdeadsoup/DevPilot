package com.obdeadsoup.devpilot.github.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.net.URI;
import java.time.Duration;

@Validated
@ConfigurationProperties(prefix = "devpilot.github")
public record GitHubIntegrationProperties(
        @NotNull URI apiBaseUrl,
        @NotNull Duration connectTimeout,
        @NotNull Duration readTimeout,
        @Min(1) @Max(16) int workerCoreThreads,
        @Min(1) @Max(32) int workerMaxThreads,
        @Min(1) @Max(10_000) int workerQueueCapacity,
        @Min(1_024) @Max(10_485_760) int webhookMaxPayloadBytes
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
}
