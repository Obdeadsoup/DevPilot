package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGrpcPropertiesTest {

    @Test
    void acceptsPositiveDeadlineAndBothTransportModes() {
        assertThat(new AgentGrpcProperties("localhost", 50051, Duration.ofMillis(1), Duration.ofMinutes(1), true)
                .isDeadlineValid()).isTrue();
        assertThat(new AgentGrpcProperties("agent.internal", 443, Duration.ofSeconds(30), Duration.ofMinutes(10), false)
                .isDeadlineValid()).isTrue();
    }

    @Test
    void rejectsSubMillisecondDeadline() {
        assertThat(new AgentGrpcProperties("localhost", 50051, Duration.ZERO, Duration.ofMinutes(1), true)
                .isDeadlineValid()).isFalse();
        assertThat(new AgentGrpcProperties(
                "localhost",
                50051,
                Duration.ofNanos(999_999),
                Duration.ofMinutes(1),
                true
        ).isDeadlineValid()).isFalse();
        assertThat(new AgentGrpcProperties(
                "localhost", 50051, Duration.ofSeconds(1), Duration.ZERO, true
        ).isDeadlineValid()).isFalse();
    }
}
