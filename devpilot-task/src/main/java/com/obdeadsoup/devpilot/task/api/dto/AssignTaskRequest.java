package com.obdeadsoup.devpilot.task.api.dto;
import jakarta.validation.constraints.Min; import jakarta.validation.constraints.Positive;
public record AssignTaskRequest(@Positive long assigneeUserId,@Min(0) long expectedVersion) { }
