package com.obdeadsoup.devpilot.project.application.command;

import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;

import java.time.LocalDateTime;
import java.util.Objects;

/** 本地 Task 写入时间线的显式内部命令，避免把 Task 冒充成 GitHub Delivery。 */
public record RecordTaskProjectActivityCommand(
        long workspaceId,
        long projectId,
        long taskId,
        long taskVersion,
        ProjectActivityType activityType,
        String title,
        String summary,
        LocalDateTime occurredAt
) {
    public RecordTaskProjectActivityCommand {
        Objects.requireNonNull(activityType, "activityType must not be null");
        Objects.requireNonNull(title, "title must not be null");
        Objects.requireNonNull(occurredAt, "occurredAt must not be null");
    }
}
