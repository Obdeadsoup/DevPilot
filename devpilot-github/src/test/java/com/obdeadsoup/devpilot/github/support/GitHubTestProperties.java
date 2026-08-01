package com.obdeadsoup.devpilot.github.support;

import com.obdeadsoup.devpilot.github.config.GitHubIntegrationProperties;

import java.net.URI;
import java.time.Duration;

public final class GitHubTestProperties {

    private GitHubTestProperties() {
    }

    public static GitHubIntegrationProperties defaults() {
        return api(3, Duration.ofMillis(200), Duration.ofSeconds(2),
                Duration.ofSeconds(3), 2, Duration.ofMillis(200));
    }

    public static GitHubIntegrationProperties withBaseUrl(URI baseUrl) {
        GitHubIntegrationProperties defaults = defaults();
        return new GitHubIntegrationProperties(
                baseUrl,
                defaults.apiVersion(),
                defaults.connectTimeout(),
                defaults.readTimeout(),
                defaults.maxReadAttempts(),
                defaults.initialBackoff(),
                defaults.maxBackoff(),
                defaults.maxSynchronousRateLimitWait(),
                defaults.maxConcurrentRequestsPerCredential(),
                defaults.concurrencyAcquireTimeout(),
                defaults.userAgent(),
                defaults.workerCoreThreads(),
                defaults.workerMaxThreads(),
                defaults.workerQueueCapacity(),
                defaults.webhookMaxPayloadBytes(),
                defaults.deliveryMaxRetries(),
                defaults.deliveryRetryInitialDelay(),
                defaults.deliveryRetryMaxDelay(),
                defaults.deliveryRecoveryScanInterval(),
                defaults.deliveryRecoveryBatchSize(),
                defaults.deliveryProcessingTimeout(),
                defaults.deliveryRecoveryEnabled()
        );
    }

    public static GitHubIntegrationProperties api(
            int maxReadAttempts,
            Duration initialBackoff,
            Duration maxBackoff,
            Duration maxSynchronousRateLimitWait,
            int maxConcurrentRequests,
            Duration concurrencyAcquireTimeout
    ) {
        return new GitHubIntegrationProperties(
                URI.create("https://api.github.com"),
                "2022-11-28",
                Duration.ofSeconds(2),
                Duration.ofSeconds(5),
                maxReadAttempts,
                initialBackoff,
                maxBackoff,
                maxSynchronousRateLimitWait,
                maxConcurrentRequests,
                concurrencyAcquireTimeout,
                "DevPilot",
                2,
                4,
                200,
                2_097_152,
                3,
                Duration.ofSeconds(10),
                Duration.ofMinutes(5),
                Duration.ofSeconds(10),
                50,
                Duration.ofMinutes(2),
                false
        );
    }
}
