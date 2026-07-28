package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubWebhookErrorCode;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GitHubDeliveryFailureClassifierTest {

    private final GitHubDeliveryFailureClassifier classifier = new GitHubDeliveryFailureClassifier();

    @Test
    void classifiesKnownPayloadAndStateErrorsAsNonRetryable() {
        assertNonRetryable(GitHubWebhookErrorCode.MALFORMED_PAYLOAD);
        assertNonRetryable(GitHubWebhookErrorCode.UNSUPPORTED_EVENT);
        assertNonRetryable(GitHubWebhookErrorCode.DELIVERY_STATE_CONFLICT);
    }

    @Test
    void classifiesUnknownRuntimeFailureAsRetryableWithSafeDetails() {
        GitHubDeliveryFailureClassifier.Classification result =
                classifier.classify(new IllegalStateException("private payload content"));

        assertThat(result.retryable()).isTrue();
        assertThat(result.stableErrorCode()).isEqualTo("PROCESSING_ERROR");
        assertThat(result.safeErrorMessage()).isEqualTo("Delivery processing failed");
        assertThat(result.safeErrorMessage()).doesNotContain("private payload content");
    }

    private void assertNonRetryable(GitHubWebhookErrorCode errorCode) {
        GitHubDeliveryFailureClassifier.Classification result =
                classifier.classify(new BusinessException(errorCode));

        assertThat(result.retryable()).isFalse();
        assertThat(result.stableErrorCode()).isEqualTo(errorCode.code());
        assertThat(result.safeErrorMessage()).isEqualTo(errorCode.message());
    }
}
