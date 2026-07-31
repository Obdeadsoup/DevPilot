package com.obdeadsoup.devpilot.github.config;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubIntegrationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GitHubIntegrationConfiguration.class)
            .withPropertyValues(
                    "devpilot.github.connect-timeout=3s",
                    "devpilot.github.read-timeout=10s",
                    "devpilot.github.worker-core-threads=2",
                    "devpilot.github.worker-max-threads=4",
                    "devpilot.github.worker-queue-capacity=200",
                    "devpilot.github.webhook-max-payload-bytes=2097152",
                    "devpilot.github.delivery-max-retries=3",
                    "devpilot.github.delivery-retry-initial-delay=10s",
                    "devpilot.github.delivery-retry-max-delay=5m",
                    "devpilot.github.delivery-recovery-scan-interval=10s",
                    "devpilot.github.delivery-recovery-batch-size=50",
                    "devpilot.github.delivery-processing-timeout=2m",
                    "devpilot.github.delivery-recovery-enabled=false"
            );

    @Test
    void bindsValidConfiguration() {
        contextRunner.run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(context).hasSingleBean(GitHubIntegrationProperties.class);

            GitHubIntegrationProperties properties = context.getBean(GitHubIntegrationProperties.class);
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.workerCoreThreads()).isEqualTo(2);
            assertThat(properties.workerMaxThreads()).isEqualTo(4);
            assertThat(properties.webhookMaxPayloadBytes()).isEqualTo(2_097_152);
            assertThat(properties.deliveryMaxRetries()).isEqualTo(3);
            assertThat(properties.deliveryRetryInitialDelay()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.deliveryRetryMaxDelay()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.deliveryRecoveryScanInterval()).isEqualTo(Duration.ofSeconds(10));
            assertThat(properties.deliveryRecoveryBatchSize()).isEqualTo(50);
            assertThat(properties.deliveryProcessingTimeout()).isEqualTo(Duration.ofMinutes(2));
            assertThat(properties.deliveryRecoveryEnabled()).isFalse();
        });
    }

    @Test
    void rejectsNonPositiveTimeout() {
        contextRunner
                .withPropertyValues("devpilot.github.connect-timeout=0ms")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("connect timeout must be positive");
                });
    }

    @Test
    void rejectsWorkerPoolWithMaxBelowCore() {
        contextRunner
                .withPropertyValues("devpilot.github.worker-max-threads=1")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("worker max threads must be greater than or equal to core threads");
                });
    }

    @Test
    void rejectsNonPositiveDeliveryDurations() {
        contextRunner
                .withPropertyValues("devpilot.github.delivery-retry-initial-delay=0s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("delivery retry initial delay must be positive");
                });
    }

    @Test
    void rejectsRetryMaxDelayBelowInitialDelay() {
        contextRunner
                .withPropertyValues("devpilot.github.delivery-retry-max-delay=5s")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "delivery retry max delay must be greater than or equal to initial delay"
                            );
                });
    }
}
