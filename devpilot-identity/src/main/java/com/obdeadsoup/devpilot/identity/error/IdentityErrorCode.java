package com.obdeadsoup.devpilot.identity.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum IdentityErrorCode implements ErrorCode {

    AUTHENTICATION_REQUIRED("IDENTITY_0401", "Authentication required", HttpStatus.UNAUTHORIZED),
    INVALID_CREDENTIALS("IDENTITY_0402", "Login name or password is incorrect", HttpStatus.UNAUTHORIZED),
    INVALID_ACCESS_TOKEN("IDENTITY_0403", "Invalid or expired access token", HttpStatus.UNAUTHORIZED),
    ACCESS_DENIED("IDENTITY_0404", "Access denied", HttpStatus.FORBIDDEN),
    ACCOUNT_UNAVAILABLE("IDENTITY_0405", "Account unavailable", HttpStatus.UNAUTHORIZED),
    USERNAME_ALREADY_EXISTS("IDENTITY_0409", "Username is already in use", HttpStatus.CONFLICT),
    EMAIL_ALREADY_EXISTS("IDENTITY_0410", "Email is already in use", HttpStatus.CONFLICT),
    VERIFICATION_COOLDOWN("IDENTITY_0411", "Verification code was sent recently", HttpStatus.TOO_MANY_REQUESTS),
    VERIFICATION_IP_LIMITED("IDENTITY_0412", "Too many verification requests from this network", HttpStatus.TOO_MANY_REQUESTS),
    VERIFICATION_CODE_INVALID("IDENTITY_0413", "Verification code is incorrect", HttpStatus.BAD_REQUEST),
    VERIFICATION_CODE_EXPIRED("IDENTITY_0414", "Verification code is expired or already used", HttpStatus.BAD_REQUEST),
    VERIFICATION_ATTEMPTS_EXCEEDED("IDENTITY_0415", "Too many incorrect verification attempts", HttpStatus.TOO_MANY_REQUESTS),
    VERIFICATION_DELIVERY_FAILED("IDENTITY_0416", "Verification email could not be sent", HttpStatus.SERVICE_UNAVAILABLE);

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
