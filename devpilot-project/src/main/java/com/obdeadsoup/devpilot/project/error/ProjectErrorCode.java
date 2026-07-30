package com.obdeadsoup.devpilot.project.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum ProjectErrorCode implements ErrorCode {

    PROJECT_NOT_FOUND("PROJECT_0404", "Project not found in workspace", HttpStatus.NOT_FOUND),
    USER_NOT_WORKSPACE_MEMBER("PROJECT_0405", "User is not an active workspace member", HttpStatus.BAD_REQUEST),
    INVALID_PROJECT_ROLE("PROJECT_0406", "Invalid project role assignment", HttpStatus.BAD_REQUEST),
    INVALID_PROJECT_KEY("PROJECT_0407", "Invalid project key", HttpStatus.BAD_REQUEST),
    PROJECT_ARCHIVED("PROJECT_0408", "Archived project cannot be modified", HttpStatus.CONFLICT),
    PROJECT_MEMBERSHIP_CONFLICT("PROJECT_0501", "Project membership conflicts with current state", HttpStatus.CONFLICT),
    PROJECT_MEMBERSHIP_VERSION_CONFLICT(
            "PROJECT_0502",
            "Project membership was changed concurrently",
            HttpStatus.CONFLICT
    ),
    PROJECT_KEY_CONFLICT("PROJECT_0503", "Project key is already in use", HttpStatus.CONFLICT),
    PROJECT_VERSION_CONFLICT("PROJECT_0504", "Project was changed concurrently", HttpStatus.CONFLICT),
    INVALID_PROJECT_STATUS_TRANSITION(
            "PROJECT_0505", "Invalid project status transition", HttpStatus.CONFLICT
    );

    private final String code;
    private final String message;
    private final HttpStatus status;

    ProjectErrorCode(String code, String message, HttpStatus status) {
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
