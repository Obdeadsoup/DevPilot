package com.obdeadsoup.devpilot.project.api.dto;

import com.obdeadsoup.devpilot.project.domain.ProjectStatus;
import com.obdeadsoup.devpilot.project.domain.ProjectVisibility;
import com.obdeadsoup.devpilot.project.persistence.entity.ProjectEntity;

import java.time.LocalDateTime;

public record ProjectResponse(
        long id,
        long workspaceId,
        String name,
        String projectKey,
        String description,
        ProjectStatus status,
        ProjectVisibility visibility,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static ProjectResponse from(ProjectEntity entity) {
        return new ProjectResponse(
                entity.id(),
                entity.workspaceId(),
                entity.name(),
                entity.projectKey(),
                entity.description(),
                ProjectStatus.valueOf(entity.status()),
                ProjectVisibility.valueOf(entity.visibility()),
                entity.version(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
