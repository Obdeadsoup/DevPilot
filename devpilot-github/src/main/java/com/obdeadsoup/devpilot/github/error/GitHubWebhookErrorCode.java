package com.obdeadsoup.devpilot.github.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum GitHubWebhookErrorCode implements ErrorCode {

    MISSING_HEADER("GITHUB_0400", "Required GitHub webhook header is missing", HttpStatus.BAD_REQUEST),
    MALFORMED_PAYLOAD("GITHUB_0401", "Malformed GitHub webhook payload", HttpStatus.BAD_REQUEST),
    UNSUPPORTED_EVENT("GITHUB_0402", "Unsupported GitHub webhook event", HttpStatus.BAD_REQUEST),
    INVALID_DELIVERY_ID("GITHUB_0403", "Invalid GitHub delivery id", HttpStatus.BAD_REQUEST),
    SIGNATURE_INVALID("GITHUB_0404", "GitHub webhook signature is invalid", HttpStatus.UNAUTHORIZED),
    REPOSITORY_NOT_FOUND("GITHUB_0405", "GitHub repository binding not found", HttpStatus.NOT_FOUND),
    REPOSITORY_DISABLED("GITHUB_0406", "GitHub repository binding is disabled", HttpStatus.FORBIDDEN),
    PAYLOAD_TOO_LARGE("GITHUB_0407", "GitHub webhook payload is too large", HttpStatus.PAYLOAD_TOO_LARGE),
    SECRET_UNAVAILABLE("GITHUB_0501", "GitHub webhook secret is unavailable", HttpStatus.SERVICE_UNAVAILABLE),
    DELIVERY_STATE_CONFLICT("GITHUB_0502", "GitHub delivery state conflict", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus status;

    GitHubWebhookErrorCode(String code, String message, HttpStatus status) {
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
