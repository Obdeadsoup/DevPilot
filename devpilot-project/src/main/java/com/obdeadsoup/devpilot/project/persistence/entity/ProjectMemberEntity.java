package com.obdeadsoup.devpilot.project.persistence.entity;

public record ProjectMemberEntity(
        long id,
        long workspaceId,
        long projectId,
        long userId,
        String role,
        String status,
        long createdBy,
        long version
) {
}
