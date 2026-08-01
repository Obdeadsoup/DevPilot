package com.obdeadsoup.devpilot.github.application.client;

import java.time.Instant;

/**
 * 统一 GitHub API 成功响应，只暴露业务 Body 与经过筛选的 Header 元数据。
 * 原始 Header、Authorization 和响应正文不会离开 HTTP 层。
 */
public record GitHubApiResponse<T>(
        int httpStatus,
        T body,
        boolean notModified,
        String etag,
        Instant lastModified,
        GitHubRateLimitSnapshot rateLimit,
        GitHubPageCursor pageCursor
) {
}
