package com.obdeadsoup.devpilot.github.application.client;

import java.time.Instant;

/**
 * GitHub API 的安全异常模型。
 *
 * <p>异常只携带稳定分类、可重试语义、HTTP 状态、retryAt、requestId 和安全消息，
 * 永远不保存 Token、Authorization、完整私有响应 Body 或底层连接地址。</p>
 */
public class GitHubApiException extends RuntimeException {

    private final GitHubApiFailureType failureType;
    private final boolean retryable;
    private final Instant retryAt;
    private final Integer httpStatus;
    private final String requestId;
    private final GitHubRateLimitSnapshot rateLimit;

    public GitHubApiException(
            GitHubApiFailureType failureType,
            boolean retryable,
            Instant retryAt,
            Integer httpStatus,
            String safeMessage,
            String requestId,
            GitHubRateLimitSnapshot rateLimit
    ) {
        super(safeMessage);
        this.failureType = failureType;
        this.retryable = retryable;
        this.retryAt = retryAt;
        this.httpStatus = httpStatus;
        this.requestId = requestId;
        this.rateLimit = rateLimit;
    }

    public GitHubApiFailureType failureType() {
        return failureType;
    }

    public boolean retryable() {
        return retryable;
    }

    public Instant retryAt() {
        return retryAt;
    }

    public Integer httpStatus() {
        return httpStatus;
    }

    public String safeMessage() {
        return getMessage();
    }

    public String requestId() {
        return requestId;
    }

    public GitHubRateLimitSnapshot rateLimit() {
        return rateLimit;
    }
}
