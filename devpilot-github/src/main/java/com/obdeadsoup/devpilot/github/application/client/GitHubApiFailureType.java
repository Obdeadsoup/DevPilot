package com.obdeadsoup.devpilot.github.application.client;

/** GitHub API 调用的稳定失败分类，不包含上游响应正文或底层连接细节。 */
public enum GitHubApiFailureType {
    VALIDATION,
    AUTHENTICATION,
    ACCESS_DENIED,
    RATE_LIMITED,
    NOT_FOUND,
    CONFLICT,
    TRANSIENT_SERVER_ERROR,
    NETWORK_ERROR,
    MALFORMED_RESPONSE,
    CREDENTIAL_UNAVAILABLE,
    CONCURRENCY_LIMITED
}
