package com.obdeadsoup.devpilot.project.persistence.entity;

import java.time.LocalDateTime;

public record ProjectEntity(
        long id,
        long workspaceId,
        String name,
        String projectKey,
        String description,
        String status,
        String visibility,
        Long createdBy,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version,
        boolean deleted
) {

    public ProjectEntity(
            long id,
            long workspaceId,
            String status,
            String visibility,
            long version,
            boolean deleted
    ) {
        this(id, workspaceId, null, null, null, status, visibility, null, null, null, version, deleted);
    }
}
