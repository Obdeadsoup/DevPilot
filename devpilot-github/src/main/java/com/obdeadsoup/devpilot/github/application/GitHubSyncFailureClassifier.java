package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiException;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiFailureType;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.Set;

/** 将同步异常压缩为稳定错误码、可重试性和安全消息，不保存响应正文、Token 或作者 Email。 */
@Component
public class GitHubSyncFailureClassifier {

    private static final Set<GitHubApiFailureType> RETRYABLE_API_FAILURES = Set.of(
            GitHubApiFailureType.RATE_LIMITED,
            GitHubApiFailureType.NETWORK_ERROR,
            GitHubApiFailureType.TRANSIENT_SERVER_ERROR,
            GitHubApiFailureType.CONCURRENCY_LIMITED
    );

    public Classification classify(RuntimeException exception) {
        if (exception instanceof GitHubApiException apiException) {
            boolean retryable = RETRYABLE_API_FAILURES.contains(apiException.failureType())
                    && apiException.retryable();
            return new Classification(
                    "GITHUB_API_" + apiException.failureType().name(),
                    apiException.safeMessage(),
                    retryable,
                    apiException.retryAt()
            );
        }
        if (exception instanceof BusinessException businessException
                && businessException.errorCode() instanceof GitHubSyncErrorCode errorCode) {
            return new Classification(errorCode.code(), errorCode.message(), false, null);
        }
        return new Classification("SYNC_PROCESSING_ERROR", "GitHub commit synchronization failed", true, null);
    }

    public record Classification(
            String stableErrorCode,
            String safeErrorMessage,
            boolean retryable,
            Instant retryAt
    ) {
    }
}
