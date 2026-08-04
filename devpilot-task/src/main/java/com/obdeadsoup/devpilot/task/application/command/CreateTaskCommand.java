package com.obdeadsoup.devpilot.task.application.command;

import com.obdeadsoup.devpilot.task.domain.TaskPriority;
import java.time.LocalDateTime;

public record CreateTaskCommand(String title, String description, TaskPriority priority, Long assigneeUserId, LocalDateTime dueAt) { }
