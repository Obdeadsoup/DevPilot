package com.obdeadsoup.devpilot.github.config;

import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdAccessor;
import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdPolicy;
import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdTaskDecorator;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;
import java.net.URI;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubIntegrationPropertiesTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(GitHubIntegrationConfiguration.class)
            .withBean(CorrelationIdPolicy.class)
            .withBean(CorrelationIdAccessor.class)
            .withBean(CorrelationIdTaskDecorator.class)
            .withPropertyValues(
                    "devpilot.github.base-url=https://api.github.com",
                    "devpilot.github.api-version=2022-11-28",
                    "devpilot.github.connect-timeout=2s",
                    "devpilot.github.read-timeout=5s",
                    "devpilot.github.max-read-attempts=3",
                    "devpilot.github.initial-backoff=200ms",
                    "devpilot.github.max-backoff=2s",
                    "devpilot.github.max-synchronous-rate-limit-wait=3s",
                    "devpilot.github.max-concurrent-requests-per-credential=2",
                    "devpilot.github.concurrency-acquire-timeout=200ms",
                    "devpilot.github.user-agent=DevPilot",
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
            assertThat(properties.baseUrl()).isEqualTo(URI.create("https://api.github.com"));
            assertThat(properties.apiVersion()).isEqualTo("2022-11-28");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.maxReadAttempts()).isEqualTo(3);
            assertThat(properties.initialBackoff()).isEqualTo(Duration.ofMillis(200));
            assertThat(properties.maxBackoff()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.maxSynchronousRateLimitWait()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.maxConcurrentRequestsPerCredential()).isEqualTo(2);
            assertThat(properties.userAgent()).isEqualTo("DevPilot");
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
    void rejectsUnsafeBaseUrlAndInvalidApiRetryBounds() {
        contextRunner
                .withPropertyValues("devpilot.github.base-url=https://evil.example")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("base URL must be the public GitHub API");
                });
        contextRunner
                .withPropertyValues("devpilot.github.max-read-attempts=6")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining("maxReadAttempts")
                            .hasStackTraceContaining("5");
                });
        contextRunner
                .withPropertyValues("devpilot.github.max-backoff=100ms")
                .run(context -> {
                    assertThat(context).hasFailed();
                    assertThat(context.getStartupFailure())
                            .hasStackTraceContaining(
                                    "max API backoff must be greater than or equal to initial backoff"
                            );
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
