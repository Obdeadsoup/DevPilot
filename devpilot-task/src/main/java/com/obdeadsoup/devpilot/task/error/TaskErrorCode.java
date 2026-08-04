package com.obdeadsoup.devpilot.task.error;

import com.obdeadsoup.devpilot.framework.error.ErrorCode;
import org.springframework.http.HttpStatus;

public enum TaskErrorCode implements ErrorCode {
    TASK_NOT_FOUND("TASK_0401", "Task 不存在", HttpStatus.NOT_FOUND),
    TASK_VERSION_CONFLICT("TASK_0501", "Task 已被其他请求修改", HttpStatus.CONFLICT),
    TASK_INVALID_TRANSITION("TASK_0502", "Task 状态流转不合法", HttpStatus.CONFLICT),
    TASK_PROJECT_ARCHIVED("TASK_0402", "Project 已归档，不能修改 Task", HttpStatus.CONFLICT),
    TASK_ASSIGNEE_NOT_ELIGIBLE("TASK_0403", "负责人没有当前 Project 的有效访问权限", HttpStatus.CONFLICT),
    TASK_ALREADY_ASSIGNED("TASK_0503", "Task 已分配给该用户", HttpStatus.CONFLICT),
    TASK_NOT_ASSIGNED("TASK_0404", "Task 当前没有负责人", HttpStatus.CONFLICT),
    TASK_PERMISSION_DENIED("TASK_0405", "没有操作该 Task 的权限", HttpStatus.FORBIDDEN),
    TASK_EXTERNAL_REFERENCE_NOT_FOUND("TASK_0406", "GitHub 快照不存在", HttpStatus.NOT_FOUND),
    TASK_EXTERNAL_REFERENCE_SCOPE_MISMATCH("TASK_0407", "GitHub 快照不属于当前 Project", HttpStatus.CONFLICT),
    TASK_EXTERNAL_RESOURCE_ALREADY_LINKED("TASK_0504", "该 GitHub 资源已关联其他 Task", HttpStatus.CONFLICT),
    TASK_LINK_NOT_FOUND("TASK_0408", "Task GitHub 关联不存在", HttpStatus.NOT_FOUND),
    TASK_LINK_VERSION_CONFLICT("TASK_0505", "Task GitHub 关联已被其他请求修改", HttpStatus.CONFLICT),
    INVALID_TASK_TITLE("TASK_0409", "Task 标题不合法", HttpStatus.BAD_REQUEST),
    INVALID_TASK_DUE_AT("TASK_0410", "Task 截止时间不合法", HttpStatus.BAD_REQUEST),
    INVALID_TASK_REASON("TASK_0411", "Task 状态变更原因不合法", HttpStatus.BAD_REQUEST);

    private final String code;
    private final String message;
    private final HttpStatus status;

    TaskErrorCode(String code, String message, HttpStatus status) {
        this.code = code; this.message = message; this.status = status;
    }
    public String code() { return code; }
    public String message() { return message; }
    public HttpStatus status() { return status; }
}
