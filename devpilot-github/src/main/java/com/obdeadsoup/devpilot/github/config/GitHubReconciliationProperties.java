package com.obdeadsoup.devpilot.github.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Commit 对账扫描、补偿窗口与有限重试的受约束配置。 */
@Validated
@ConfigurationProperties(prefix = "devpilot.github.reconciliation")
public record GitHubReconciliationProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5m") @NotNull Duration scanInterval,
        @DefaultValue("25") @Min(1) @Max(1_000) int batchSize,
        @DefaultValue("7d") @NotNull Duration initialLookback,
        @DefaultValue("5m") @NotNull Duration overlapWindow,
        @DefaultValue("5") @Min(1) @Max(20) int maxRunAttempts,
        @DefaultValue("100") @Min(1) @Max(100) int perPage,
        @DefaultValue("15m") @NotNull Duration runTimeout,
        @DefaultValue("1m") @NotNull Duration retryInitialDelay,
        @DefaultValue("30m") @NotNull Duration retryMaxDelay
) {

    @AssertTrue(message = "reconciliation durations must be positive")
    public boolean areDurationsPositive() {
        return positive(scanInterval)
                && positive(initialLookback)
                && positive(overlapWindow)
                && positive(runTimeout)
                && positive(retryInitialDelay);
    }

    @AssertTrue(message = "reconciliation retry max delay must be at least initial delay")
    public boolean isRetryDelayRangeValid() {
        return retryInitialDelay != null
                && retryMaxDelay != null
                && retryMaxDelay.compareTo(retryInitialDelay) >= 0;
    }

    private boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
