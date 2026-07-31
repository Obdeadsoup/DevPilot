package com.obdeadsoup.devpilot.github.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum GitHubRepositoryErrorCode implements ErrorCode {

    INVALID_REPOSITORY_REFERENCE(
            "GITHUB_REPOSITORY_0400", "Invalid GitHub repository reference", HttpStatus.BAD_REQUEST
    ),
    REPOSITORY_BINDING_NOT_FOUND(
            "GITHUB_REPOSITORY_0404", "GitHub repository binding not found", HttpStatus.NOT_FOUND
    ),
    GITHUB_API_CREDENTIAL_UNAVAILABLE(
            "GITHUB_REPOSITORY_0500", "GitHub API credential is unavailable", HttpStatus.SERVICE_UNAVAILABLE
    ),
    REPOSITORY_ALREADY_BOUND(
            "GITHUB_REPOSITORY_0501", "GitHub repository is already bound to this project", HttpStatus.CONFLICT
    ),
    REPOSITORY_BOUND_TO_ANOTHER_PROJECT(
            "GITHUB_REPOSITORY_0502", "GitHub repository is already bound to another project", HttpStatus.CONFLICT
    ),
    REPOSITORY_BINDING_DISABLED(
            "GITHUB_REPOSITORY_0503", "GitHub repository binding is disabled", HttpStatus.CONFLICT
    ),
    REPOSITORY_BINDING_VERSION_CONFLICT(
            "GITHUB_REPOSITORY_0504", "GitHub repository binding was changed concurrently", HttpStatus.CONFLICT
    ),
    GITHUB_REPOSITORY_NOT_ACCESSIBLE(
            "GITHUB_REPOSITORY_0505", "GitHub repository is not accessible", HttpStatus.BAD_GATEWAY
    ),
    GITHUB_REPOSITORY_ID_MISMATCH(
            "GITHUB_REPOSITORY_0506", "GitHub repository identity does not match the binding", HttpStatus.CONFLICT
    ),
    WEBHOOK_SECRET_UNAVAILABLE(
            "GITHUB_REPOSITORY_0507", "GitHub webhook secret is unavailable", HttpStatus.SERVICE_UNAVAILABLE
    ),
    INVALID_BINDING_STATUS_TRANSITION(
            "GITHUB_REPOSITORY_0508", "Invalid GitHub repository binding status transition", HttpStatus.CONFLICT
    ),
    GITHUB_API_AUTHENTICATION_FAILED(
            "GITHUB_REPOSITORY_0509", "GitHub API authentication failed", HttpStatus.BAD_GATEWAY
    ),
    GITHUB_API_FORBIDDEN(
            "GITHUB_REPOSITORY_0510", "GitHub API denied repository access", HttpStatus.BAD_GATEWAY
    ),
    GITHUB_API_RATE_LIMITED(
            "GITHUB_REPOSITORY_0511", "GitHub API rate limit was exceeded", HttpStatus.SERVICE_UNAVAILABLE
    ),
    GITHUB_API_UNAVAILABLE(
            "GITHUB_REPOSITORY_0512", "GitHub API is unavailable", HttpStatus.SERVICE_UNAVAILABLE
    ),
    GITHUB_API_RESPONSE_INVALID(
            "GITHUB_REPOSITORY_0513", "GitHub API returned an invalid repository response", HttpStatus.BAD_GATEWAY
    );

    private final String code;
    private final String message;
    private final HttpStatus status;

    GitHubRepositoryErrorCode(String code, String message, HttpStatus status) {
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
