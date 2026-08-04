package com.obdeadsoup.devpilot.task.api.dto;
import com.obdeadsoup.devpilot.task.domain.TaskPriority;
import jakarta.validation.constraints.NotBlank; import jakarta.validation.constraints.Size; import java.time.LocalDateTime;
public record CreateTaskRequest(@NotBlank @Size(max=255) String title,@Size(max=10000) String description,TaskPriority priority,Long assigneeUserId,LocalDateTime dueAt) { }
