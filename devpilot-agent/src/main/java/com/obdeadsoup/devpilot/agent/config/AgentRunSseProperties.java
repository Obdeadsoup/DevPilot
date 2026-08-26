package com.obdeadsoup.devpilot.agent.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** AgentRun SSE 的连接、Heartbeat、有界 replay 与 terminal 缓存保留策略。 */
@Validated
@ConfigurationProperties("devpilot.agent.sse")
public record AgentRunSseProperties(
        boolean enabled,
        @NotNull Duration timeout,
        @NotNull Duration heartbeatInterval,
        @Min(1) @Max(20) int maxConnectionsPerRun,
        @Min(1) @Max(1024) int replayCapacity,
        @NotNull Duration terminalRetention
) {
    @AssertTrue(message = "Agent SSE durations must be positive")
    public boolean isValid() {
        return positive(timeout) && positive(heartbeatInterval) && positive(terminalRetention);
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
