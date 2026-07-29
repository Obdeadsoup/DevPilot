package com.obdeadsoup.devpilot.identity.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum WorkspaceErrorCode implements ErrorCode {

    WORKSPACE_NOT_FOUND("IDENTITY_0406", "Workspace not found", HttpStatus.NOT_FOUND),
    USER_NOT_ACTIVE("IDENTITY_0407", "Target user is not active", HttpStatus.BAD_REQUEST),
    INVALID_WORKSPACE_ROLE("IDENTITY_0408", "Invalid workspace role assignment", HttpStatus.BAD_REQUEST),
    MEMBERSHIP_CONFLICT("IDENTITY_0501", "Workspace membership conflicts with current state", HttpStatus.CONFLICT),
    MEMBERSHIP_VERSION_CONFLICT("IDENTITY_0502", "Workspace membership was changed concurrently", HttpStatus.CONFLICT),
    OWNERSHIP_TRANSFER_CONFLICT("IDENTITY_0503", "Workspace ownership was changed concurrently", HttpStatus.CONFLICT);

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
