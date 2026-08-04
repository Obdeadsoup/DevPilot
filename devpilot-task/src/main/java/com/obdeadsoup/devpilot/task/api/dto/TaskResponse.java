package com.obdeadsoup.devpilot.task.api.dto;

import com.obdeadsoup.devpilot.task.domain.TaskPriority;
import com.obdeadsoup.devpilot.task.domain.TaskStatus;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import java.time.LocalDateTime;

public record TaskResponse(long id,String displayKey,String title,String description,TaskStatus status,TaskPriority priority,
        long reporterUserId,Long assigneeUserId,LocalDateTime dueAt,LocalDateTime completedAt,LocalDateTime canceledAt,
        LocalDateTime createdAt,LocalDateTime updatedAt,long version) {
    public static TaskResponse from(TaskEntity task,String projectKey,boolean includeDescription){
        return new TaskResponse(task.getId(),projectKey+"-"+task.getId(),task.getTitle(),includeDescription?task.getDescription():null,
                TaskStatus.valueOf(task.getStatus()),TaskPriority.valueOf(task.getPriority()),task.getReporterUserId(),task.getAssigneeUserId(),
                task.getDueAt(),task.getCompletedAt(),task.getCanceledAt(),task.getCreatedAt(),task.getUpdatedAt(),task.getVersion());
    }
}
