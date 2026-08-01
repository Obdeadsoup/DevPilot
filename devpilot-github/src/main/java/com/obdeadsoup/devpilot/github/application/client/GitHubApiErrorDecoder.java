package com.obdeadsoup.devpilot.github.application.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpHeaders;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;

/**
 * 将 GitHub HTTP/网络失败解码成稳定、安全且可供 Retry Policy 判断的异常。
 *
 * <p>403 只有在 Retry-After、Remaining=0 或安全解析的限流消息提供证据时才归类为
 * RATE_LIMITED；Reset 用来计算等待时间，不能单独把普通权限不足误判成可重试限流。</p>
 */
public final class GitHubApiErrorDecoder {

    private static final Set<Integer> TRANSIENT_SERVER_STATUSES = Set.of(500, 502, 503, 504);

    private final ObjectMapper objectMapper;
    private final GitHubRateLimitParser rateLimitParser;
    private final Clock clock;

    public GitHubApiErrorDecoder(ObjectMapper objectMapper, GitHubRateLimitParser rateLimitParser, Clock clock) {
        this.objectMapper = objectMapper;
        this.rateLimitParser = rateLimitParser;
        this.clock = clock;
    }

    public GitHubApiException decode(int status, HttpHeaders headers, byte[] safeLimitedBody) {
        GitHubRateLimitSnapshot snapshot = rateLimitParser.parse(headers);
        String safeErrorType = safeErrorType(safeLimitedBody);
        if (status == 400 || status == 422) {
            return exception(GitHubApiFailureType.VALIDATION, false, status,
                    "GitHub API rejected the request", snapshot, null);
        }
        if (status == 401) {
            return exception(GitHubApiFailureType.AUTHENTICATION, false, status,
                    "GitHub API authentication failed", snapshot, null);
        }
        if (status == 403 && isRateLimited(snapshot, safeErrorType)) {
            return rateLimited(status, snapshot);
        }
        if (status == 403) {
            return exception(GitHubApiFailureType.ACCESS_DENIED, false, status,
                    "GitHub API denied access", snapshot, null);
        }
        if (status == 404) {
            return exception(GitHubApiFailureType.NOT_FOUND, false, status,
                    "GitHub resource was not found", snapshot, null);
        }
        if (status == 409) {
            return exception(GitHubApiFailureType.CONFLICT, false, status,
                    "GitHub API reported a conflict", snapshot, null);
        }
        if (status == 429) {
            return rateLimited(status, snapshot);
        }
        if (TRANSIENT_SERVER_STATUSES.contains(status)) {
            return exception(GitHubApiFailureType.TRANSIENT_SERVER_ERROR, true, status,
                    "GitHub API is temporarily unavailable", snapshot, null);
        }
        return exception(GitHubApiFailureType.VALIDATION, false, status,
                "GitHub API request failed", snapshot, null);
    }

    public GitHubApiException networkFailure() {
        return exception(GitHubApiFailureType.NETWORK_ERROR, true, null,
                "GitHub API network request failed", null, null);
    }

    public GitHubApiException malformedResponse(GitHubRateLimitSnapshot snapshot) {
        return exception(GitHubApiFailureType.MALFORMED_RESPONSE, false, null,
                "GitHub API returned a malformed response", snapshot, null);
    }

    private GitHubApiException rateLimited(int status, GitHubRateLimitSnapshot snapshot) {
        Instant retryAt = null;
        if (snapshot.retryAfter() != null) {
            retryAt = clock.instant().plus(snapshot.retryAfter());
        } else if (snapshot.resetAt() != null) {
            retryAt = snapshot.resetAt();
        }
        return exception(GitHubApiFailureType.RATE_LIMITED, true, status,
                "GitHub API rate limit was exceeded", snapshot, retryAt);
    }

    private boolean isRateLimited(GitHubRateLimitSnapshot snapshot, String safeErrorType) {
        // 普通 403 是权限错误；只有 Header 或安全错误类型明确给出限流证据时才允许 Retry。
        return snapshot.retryAfter() != null
                || Long.valueOf(0L).equals(snapshot.remaining())
                || "PRIMARY_RATE_LIMIT".equals(safeErrorType)
                || "SECONDARY_RATE_LIMIT".equals(safeErrorType);
    }

    private String safeErrorType(byte[] body) {
        if (body == null || body.length == 0) {
            return null;
        }
        try {
            JsonNode root = objectMapper.readTree(body);
            String message = root.path("message").asText("").toLowerCase(Locale.ROOT);
            if (message.contains("secondary rate limit") || message.contains("abuse detection")) {
                return "SECONDARY_RATE_LIMIT";
            }
            if (message.contains("rate limit exceeded")) {
                return "PRIMARY_RATE_LIMIT";
            }
            return null;
        } catch (IOException exception) {
            return null;
        }
    }

    private GitHubApiException exception(
            GitHubApiFailureType type,
            boolean retryable,
            Integer status,
            String message,
            GitHubRateLimitSnapshot snapshot,
            Instant retryAt
    ) {
        return new GitHubApiException(
                type,
                retryable,
                retryAt,
                status,
                message,
                snapshot == null ? null : snapshot.requestId(),
                snapshot
        );
    }
}
