package com.obdeadsoup.devpilot.github.application.client;

import java.time.Duration;
import java.time.Instant;

/** 一次 GitHub 响应中经过安全解析的 Rate Limit 与请求追踪信息。 */
public record GitHubRateLimitSnapshot(
        Long limit,
        Long remaining,
        Long used,
        Instant resetAt,
        String resource,
        Duration retryAfter,
        String requestId
) {
}
