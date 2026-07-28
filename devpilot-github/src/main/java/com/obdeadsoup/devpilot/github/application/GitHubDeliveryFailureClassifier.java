package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubWebhookErrorCode;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class GitHubDeliveryFailureClassifier {

    private static final Set<GitHubWebhookErrorCode> NON_RETRYABLE_ERRORS = Set.of(
            GitHubWebhookErrorCode.MALFORMED_PAYLOAD,
            GitHubWebhookErrorCode.UNSUPPORTED_EVENT,
            GitHubWebhookErrorCode.DELIVERY_STATE_CONFLICT
    );

    public Classification classify(RuntimeException exception) {
        if (exception instanceof BusinessException businessException
                && businessException.errorCode() instanceof GitHubWebhookErrorCode errorCode
                && NON_RETRYABLE_ERRORS.contains(errorCode)) {
            return new Classification(false, errorCode.code(), errorCode.message());
        }
        return new Classification(true, "PROCESSING_ERROR", "Delivery processing failed");
    }

    public record Classification(
            boolean retryable,
            String stableErrorCode,
            String safeErrorMessage
    ) {
    }
}
