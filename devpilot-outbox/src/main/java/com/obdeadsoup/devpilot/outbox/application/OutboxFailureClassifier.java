package com.obdeadsoup.devpilot.outbox.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.outbox.domain.OutboxFailureType;
import com.obdeadsoup.devpilot.outbox.domain.OutboxProcessingException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.TransientDataAccessException;
import org.springframework.stereotype.Component;

/** 把 Handler/数据库异常压缩为稳定、可安全落库的 Retry 或 DEAD 决策。 */
@Component
public class OutboxFailureClassifier {

    public OutboxFailureDecision classify(RuntimeException exception) {
        if (exception instanceof OutboxProcessingException processing) {
            return decision(processing.failureType());
        }
        if (exception instanceof BusinessException business) {
            String code = business.errorCode().code();
            OutboxFailureType type = code.equals("NOTIFICATION_0403")
                    ? OutboxFailureType.SCOPE_CONFLICT
                    : OutboxFailureType.INVALID_EVENT;
            return decision(type);
        }
        if (exception instanceof TransientDataAccessException) {
            return decision(OutboxFailureType.TRANSIENT_DATABASE);
        }
        if (exception instanceof DataIntegrityViolationException) {
            return decision(OutboxFailureType.INVALID_EVENT);
        }
        return decision(OutboxFailureType.TRANSIENT_HANDLER);
    }

    private OutboxFailureDecision decision(OutboxFailureType type) {
        return new OutboxFailureDecision(
                type, type.retryable(), type.name(), safeMessage(type));
    }

    private String safeMessage(OutboxFailureType type) {
        return switch (type) {
            case MALFORMED_PAYLOAD -> "Malformed outbox payload";
            case UNSUPPORTED_SCHEMA -> "Unsupported outbox schema version";
            case UNKNOWN_EVENT_TYPE -> "Unknown outbox event type";
            case INVALID_EVENT -> "Invalid outbox event";
            case SCOPE_CONFLICT -> "Outbox notification scope conflict";
            case TRANSIENT_DATABASE -> "Temporary database failure";
            case TRANSIENT_HANDLER -> "Temporary outbox handler failure";
            case PROCESSING_TIMEOUT -> "Outbox processing timed out";
        };
    }
}
