package com.obdeadsoup.devpilot.github.application.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.http.HttpMethod;

import java.util.concurrent.TimeUnit;

/**
 * GitHub API 的低基数指标门面。
 *
 * <p>标签只使用内部 operation、HTTP method/status 和稳定 failureType，不使用 Repository、
 * userId、requestId 或 Credential Reference。</p>
 */
public final class GitHubApiMetrics {

    private final MeterRegistry registry;

    public GitHubApiMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void request(String operation, HttpMethod method, Integer status, long durationNanos) {
        String statusTag = status == null ? "IO" : Integer.toString(status);
        registry.counter(
                "github.api.requests",
                "operation", operation,
                "method", method.name(),
                "status", statusTag
        ).increment();
        Timer.builder("github.api.duration")
                .tags("operation", operation, "method", method.name())
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void failure(String operation, GitHubApiFailureType failureType) {
        registry.counter(
                "github.api.failures",
                "operation", operation,
                "failureType", failureType.name()
        ).increment();
        if (failureType == GitHubApiFailureType.RATE_LIMITED) {
            registry.counter("github.api.rate_limited", "operation", operation).increment();
        }
    }

    public void retry(String operation, GitHubApiFailureType failureType) {
        registry.counter(
                "github.api.retries",
                "operation", operation,
                "failureType", failureType.name()
        ).increment();
    }
}
