package com.obdeadsoup.devpilot.outbox.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** Outbox 扫描、有限重试、卡死恢复和有界线程池配置；非法边界会阻止应用启动。 */
@Validated
@ConfigurationProperties("devpilot.outbox")
public record OutboxProperties(
        boolean enabled,
        @NotNull Duration scanInterval,
        @Min(1) @Max(1000) int batchSize,
        @Min(0) @Max(20) int maxRetries,
        @NotNull Duration initialBackoff,
        @NotNull Duration maxBackoff,
        @NotNull Duration processingTimeout,
        @Min(1) @Max(32) int workerCoreThreads,
        @Min(1) @Max(64) int workerMaxThreads,
        @Min(1) @Max(10000) int workerQueueCapacity) {

    @AssertTrue(message = "outbox durations and executor bounds are invalid")
    public boolean isValid() {
        return positive(scanInterval)
                && positive(initialBackoff)
                && positive(maxBackoff)
                && positive(processingTimeout)
                && maxBackoff.compareTo(initialBackoff) >= 0
                && workerMaxThreads >= workerCoreThreads;
    }

    private boolean positive(Duration duration) {
        return duration != null && !duration.isZero() && !duration.isNegative();
    }
}
