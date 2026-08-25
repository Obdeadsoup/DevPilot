package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class AgentGrpcPropertiesTest {

    @Test
    void acceptsPositiveDeadlineAndBothTransportModes() {
        assertThat(new AgentGrpcProperties("localhost", 50051, Duration.ofMillis(1), true)
                .isDeadlineValid()).isTrue();
        assertThat(new AgentGrpcProperties("agent.internal", 443, Duration.ofSeconds(30), false)
                .isDeadlineValid()).isTrue();
    }

    @Test
    void rejectsSubMillisecondDeadline() {
        assertThat(new AgentGrpcProperties("localhost", 50051, Duration.ZERO, true)
                .isDeadlineValid()).isFalse();
        assertThat(new AgentGrpcProperties(
                "localhost",
                50051,
                Duration.ofNanos(999_999),
                true
        ).isDeadlineValid()).isFalse();
    }
}
