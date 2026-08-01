package com.obdeadsoup.devpilot.github.application.credential;

import java.time.Instant;
import java.util.Objects;

/**
 * HTTP 层短暂持有的 GitHub Access Token，过期时间为空表示当前 PAT 没有本地可见的到期信息。
 *
 * <p>该类型故意覆盖 {@link #toString()}，避免 record 默认输出或调试日志意外暴露原始 Token。</p>
 */
public final class GitHubAccessToken {

    private final String value;
    private final Instant expiresAt;

    public GitHubAccessToken(String value, Instant expiresAt) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("GitHub access token must not be blank");
        }
        this.value = value;
        this.expiresAt = expiresAt;
    }

    public String value() {
        return value;
    }

    public Instant expiresAt() {
        return expiresAt;
    }

    @Override
    public String toString() {
        return "GitHubAccessToken[value=<redacted>, expiresAt=" + expiresAt + "]";
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof GitHubAccessToken that)) {
            return false;
        }
        return value.equals(that.value) && Objects.equals(expiresAt, that.expiresAt);
    }

    @Override
    public int hashCode() {
        return Objects.hash(value, expiresAt);
    }
}
