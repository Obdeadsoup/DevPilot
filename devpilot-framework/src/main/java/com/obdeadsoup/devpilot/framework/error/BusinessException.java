package com.obdeadsoup.devpilot.framework.error;

import java.util.Objects;

public class BusinessException extends RuntimeException {

    private final ErrorCode errorCode;

    public BusinessException(ErrorCode errorCode) {
        this(errorCode, Objects.requireNonNull(errorCode, "errorCode must not be null").message());
    }

    public BusinessException(ErrorCode errorCode, String message) {
        super(Objects.requireNonNull(message, "message must not be null"));
        this.errorCode = Objects.requireNonNull(errorCode, "errorCode must not be null");
    }

    public ErrorCode errorCode() {
        return errorCode;
    }
}
