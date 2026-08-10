package com.obdeadsoup.devpilot.notification.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/** SSE 连接超时、Heartbeat 和单用户连接上限配置；Channel 配置不改变 Notification 可靠性。 */
@Validated
@ConfigurationProperties("devpilot.notification.sse")
public record NotificationSseProperties(
        boolean enabled,
        @NotNull Duration timeout,
        @NotNull Duration heartbeatInterval,
        @Min(1) @Max(20) int maxConnectionsPerUser) {

    @AssertTrue(message = "SSE durations must be positive")
    public boolean isValid() {
        return positive(timeout) && positive(heartbeatInterval);
    }

    private boolean positive(Duration value) {
        return value != null && !value.isZero() && !value.isNegative();
    }
}
