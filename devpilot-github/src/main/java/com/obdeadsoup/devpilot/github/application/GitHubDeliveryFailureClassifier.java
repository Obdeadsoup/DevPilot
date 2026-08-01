package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubWebhookErrorCode;
import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * 把 Delivery 处理异常归一为可重试性、稳定错误码和安全消息。
 * 日志及数据库只保存分类结果，不保存原始私有 Payload。
 */
@Component
public class GitHubDeliveryFailureClassifier {

    private static final Set<GitHubWebhookErrorCode> NON_RETRYABLE_ERRORS = Set.of(
            GitHubWebhookErrorCode.MALFORMED_PAYLOAD,
            GitHubWebhookErrorCode.UNSUPPORTED_EVENT,
            GitHubWebhookErrorCode.DELIVERY_STATE_CONFLICT
    );

    /** 将业务异常分类；Payload、事件类型和状态冲突属于确定性失败，不做 Retry。 */
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
