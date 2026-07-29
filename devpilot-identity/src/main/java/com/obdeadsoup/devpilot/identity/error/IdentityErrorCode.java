package com.obdeadsoup.devpilot.identity.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum IdentityErrorCode implements ErrorCode {

    AUTHENTICATION_REQUIRED("IDENTITY_0401", "Authentication required", HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS("IDENTITY_0402", "Login name or password is incorrect", HttpStatus.UNAUTHORIZED),
    INVALID_ACCESS_TOKEN("IDENTITY_0403", "Invalid or expired access token", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED("IDENTITY_0404", "Access denied", HttpStatus.FORBIDDEN),
    ACCOUNT_UNAVAILABLE("IDENTITY_0405", "Account unavailable", HttpStatus.UNAUTHORIZED);

    private final String code;
    private final String message;
    private final HttpStatus status;

    IdentityErrorCode(String code, String message, HttpStatus status) {
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
