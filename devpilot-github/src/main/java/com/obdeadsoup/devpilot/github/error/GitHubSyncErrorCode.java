package com.obdeadsoup.devpilot.github.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum GitHubSyncErrorCode implements ErrorCode {
    SYNC_RUN_NOT_FOUND("GITHUB_SYNC_0404", "GitHub sync run not found", HttpStatus.NOT_FOUND),
    SYNC_TARGET_UNAVAILABLE("GITHUB_SYNC_0500", "GitHub sync target is unavailable", HttpStatus.CONFLICT),
    REPOSITORY_ID_MISMATCH("GITHUB_SYNC_0501", "GitHub repository identity does not match the binding", HttpStatus.CONFLICT),
    SYNC_STATE_CONFLICT("GITHUB_SYNC_0502", "GitHub sync state was changed concurrently", HttpStatus.CONFLICT),
    CHECKPOINT_CONFLICT("GITHUB_SYNC_0503", "GitHub sync checkpoint was changed concurrently", HttpStatus.CONFLICT),
    COMMIT_SCOPE_CONFLICT("GITHUB_SYNC_0504", "GitHub commit scope conflicts with the repository binding", HttpStatus.CONFLICT),
    COMMIT_RESPONSE_INVALID("GITHUB_SYNC_0505", "GitHub commit data is invalid", HttpStatus.BAD_GATEWAY);

    private final String code;
    private final String message;
    private final HttpStatus status;

    GitHubSyncErrorCode(String code, String message, HttpStatus status) {
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
