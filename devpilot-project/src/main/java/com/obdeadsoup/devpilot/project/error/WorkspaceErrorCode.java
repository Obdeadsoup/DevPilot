package com.obdeadsoup.devpilot.project.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum WorkspaceErrorCode implements ErrorCode {

    WORKSPACE_NOT_FOUND("IDENTITY_0406", "Workspace not found", HttpStatus.NOT_FOUND),
    USER_NOT_ACTIVE("IDENTITY_0407", "Target user is not active", HttpStatus.BAD_REQUEST),
    INVALID_WORKSPACE_ROLE("IDENTITY_0408", "Invalid workspace role assignment", HttpStatus.BAD_REQUEST),
    WORKSPACE_DISABLED("WORKSPACE_0409", "Workspace is disabled", HttpStatus.CONFLICT),
    INVALID_WORKSPACE_STATUS_TRANSITION(
            "WORKSPACE_0410", "Invalid workspace status transition", HttpStatus.CONFLICT
    ),
    INVALID_WORKSPACE_SLUG("WORKSPACE_0411", "Invalid workspace slug", HttpStatus.BAD_REQUEST),
    MEMBERSHIP_CONFLICT("IDENTITY_0501", "Workspace membership conflicts with current state", HttpStatus.CONFLICT),
    MEMBERSHIP_VERSION_CONFLICT("IDENTITY_0502", "Workspace membership was changed concurrently", HttpStatus.CONFLICT),
    OWNERSHIP_TRANSFER_CONFLICT("IDENTITY_0503", "Workspace ownership was changed concurrently", HttpStatus.CONFLICT),
    WORKSPACE_SLUG_CONFLICT("WORKSPACE_0504", "Workspace slug is already in use", HttpStatus.CONFLICT),
    WORKSPACE_VERSION_CONFLICT("WORKSPACE_0505", "Workspace was changed concurrently", HttpStatus.CONFLICT);

    private final String code;
    private final String message;
    private final HttpStatus status;

    WorkspaceErrorCode(String code, String message, HttpStatus status) {
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
