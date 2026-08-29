package com.obdeadsoup.devpilot.agent.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Java Core→Python 长流的熔断与并发容量配置。 */
@Validated
@ConfigurationProperties("devpilot.agent.resilience")
public record AgentResilienceProperties(
        @Min(1) int maxActiveRuns,
        @Min(2) int slidingWindowSize,
        @Min(1) int minimumCalls,
        @Min(1) @Max(100) float failureRateThreshold,
        @NotNull Duration openStateDuration,
        @Min(1) int halfOpenPermittedCalls
) {
}
