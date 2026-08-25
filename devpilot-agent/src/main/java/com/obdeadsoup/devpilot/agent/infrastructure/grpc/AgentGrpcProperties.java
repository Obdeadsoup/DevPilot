package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/** Python Agent Runtime endpoint、传输模式和每次 Unary RPC 的 Deadline 配置。 */
@Validated
@ConfigurationProperties("devpilot.agent.grpc")
public record AgentGrpcProperties(
        @NotBlank String host,
        @Min(1) @Max(65535) int port,
        @NotNull Duration deadline,
        boolean plaintext
) {

    @AssertTrue(message = "deadline must be at least 1ms")
    public boolean isDeadlineValid() {
        return deadline != null && deadline.compareTo(Duration.ofMillis(1)) >= 0;
    }
}
