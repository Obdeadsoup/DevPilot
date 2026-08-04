package com.obdeadsoup.devpilot.task.api.dto;
import com.obdeadsoup.devpilot.task.domain.TaskPriority; import java.time.LocalDateTime;
public record CreateTaskFromIssueRequest(TaskPriority priority,Long assigneeUserId,LocalDateTime dueAt) { }
