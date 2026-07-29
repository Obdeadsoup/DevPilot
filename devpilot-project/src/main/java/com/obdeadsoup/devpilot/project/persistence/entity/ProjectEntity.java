package com.obdeadsoup.devpilot.project.persistence.entity;

public record ProjectEntity(
        long id,
        long workspaceId,
        String status,
        String visibility,
        long version,
        boolean deleted
) {
}
