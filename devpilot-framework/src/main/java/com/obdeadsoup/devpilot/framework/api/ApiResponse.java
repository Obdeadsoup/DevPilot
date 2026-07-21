package com.obdeadsoup.devpilot.framework.api;

import com.obdeadsoup.devpilot.framework.error.CommonErrorCode;
import com.obdeadsoup.devpilot.framework.error.ErrorCode;

import java.util.Objects;

public record ApiResponse<T>(String code, String message, T data) {

    public ApiResponse {
        Objects.requireNonNull(code, "code must not be null");
        Objects.requireNonNull(message, "message must not be null");
    }

    public static <T> ApiResponse<T> success(T data) {
        return new ApiResponse<>(
                CommonErrorCode.SUCCESS.code(),
                CommonErrorCode.SUCCESS.message(),
                data
        );
    }

    public static ApiResponse<Void> failure(ErrorCode errorCode) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return failure(errorCode, errorCode.message());
    }

    public static ApiResponse<Void> failure(ErrorCode errorCode, String message) {
        Objects.requireNonNull(errorCode, "errorCode must not be null");
        return new ApiResponse<>(errorCode.code(), message, null);
    }
}
