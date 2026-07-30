package com.obdeadsoup.devpilot.project.persistence.entity;

import java.time.LocalDateTime;

public record WorkspaceEntity(
        long id,
        String name,
        String slug,
        String description,
        Long ownerUserId,
        String status,
        long version,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        boolean deleted
) {

    public WorkspaceEntity(long id, Long ownerUserId, String status, long version, boolean deleted) {
        this(id, null, null, null, ownerUserId, status, version, null, null, deleted);
    }
}
