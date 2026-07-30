package com.obdeadsoup.devpilot.project.api.dto;

import com.obdeadsoup.devpilot.project.domain.WorkspaceStatus;
import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceEntity;

import java.time.LocalDateTime;

public record WorkspaceResponse(
        long id,
        String name,
        String slug,
        String description,
        Long ownerUserId,
        WorkspaceStatus status,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt
) {

    public static WorkspaceResponse from(WorkspaceEntity entity) {
        return new WorkspaceResponse(
                entity.id(),
                entity.name(),
                entity.slug(),
                entity.description(),
                entity.ownerUserId(),
                WorkspaceStatus.valueOf(entity.status()),
                entity.version(),
                entity.createdAt(),
                entity.updatedAt()
        );
    }
}
