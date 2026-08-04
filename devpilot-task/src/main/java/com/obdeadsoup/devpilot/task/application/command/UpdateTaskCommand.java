package com.obdeadsoup.devpilot.task.application.command;
import com.obdeadsoup.devpilot.task.domain.TaskPriority;
import java.time.LocalDateTime;
public record UpdateTaskCommand(String title, String description, TaskPriority priority, LocalDateTime dueAt, long expectedVersion) { }
