package com.obdeadsoup.devpilot.framework.error;

import org.springframework.http.HttpStatus;

public enum CommonErrorCode implements ErrorCode {

    SUCCESS("COMMON_0000", "Success", HttpStatus.OK),
    INVALID_REQUEST("COMMON_0400", "Invalid request", HttpStatus.BAD_REQUEST),
    INTERNAL_ERROR("COMMON_0500", "Internal server error", HttpStatus.INTERNAL_SERVER_ERROR);

    private final String code;
    private final String message;
    private final HttpStatus status;

    CommonErrorCode(String code, String message, HttpStatus status) {
        this.code = code;
        this.message = message;
        this.status = status;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }

    @Override
    public HttpStatus status() {
        return status;
    }
}
