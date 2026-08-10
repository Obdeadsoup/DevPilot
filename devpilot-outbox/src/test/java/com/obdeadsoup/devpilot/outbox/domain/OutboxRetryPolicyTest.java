package com.obdeadsoup.devpilot.outbox.domain;

import static org.assertj.core.api.Assertions.assertThat;

import com.obdeadsoup.devpilot.outbox.config.OutboxProperties;
import java.time.Duration;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;

class OutboxRetryPolicyTest {

    private final OutboxRetryPolicy policy = new OutboxRetryPolicy(new OutboxProperties(
            true, Duration.ofSeconds(5), 100, 3, Duration.ofSeconds(1), Duration.ofSeconds(5),
            Duration.ofMinutes(2), 2, 4, 200));

    @Test
    void appliesExponentialBackoffWithCap() {
        LocalDateTime now = LocalDateTime.of(2026, 8, 10, 10, 0);
        assertThat(policy.nextRetryAt(now, 1)).isEqualTo(now.plusSeconds(1));
        assertThat(policy.nextRetryAt(now, 3)).isEqualTo(now.plusSeconds(4));
        assertThat(policy.nextRetryAt(now, 10)).isEqualTo(now.plusSeconds(5));
    }

    @Test
    void exhaustsOnlyAfterConfiguredRetryCount() {
        assertThat(policy.exhausted(3)).isFalse();
        assertThat(policy.exhausted(4)).isTrue();
    }
}
