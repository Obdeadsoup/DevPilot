package com.obdeadsoup.devpilot.github.application.client;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
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
        Counter.builder("devpilot.github.api.requests").description("GitHub API 请求累计次数")
                .tags("operation", operation, "method", method.name(), "status", statusTag)
                .register(registry).increment();
        Timer.builder("devpilot.github.api.duration")
                .description("GitHub API 请求耗时")
                .tags("operation", operation, "method", method.name())
                .register(registry)
                .record(durationNanos, TimeUnit.NANOSECONDS);
    }

    public void failure(String operation, GitHubApiFailureType failureType) {
        Counter.builder("devpilot.github.api.failures").description("GitHub API 失败累计次数")
                .tags("operation", operation, "failure_type", failureType.name())
                .register(registry).increment();
        if (failureType == GitHubApiFailureType.RATE_LIMITED) {
            Counter.builder("devpilot.github.api.rate.limited").description("GitHub API 限流累计次数")
                    .tag("operation", operation).register(registry).increment();
        }
    }

    public void retry(String operation, GitHubApiFailureType failureType) {
        Counter.builder("devpilot.github.api.retries").description("GitHub API 同步 Retry 累计次数")
                .tags("operation", operation, "failure_type", failureType.name())
                .register(registry).increment();
    }
}
