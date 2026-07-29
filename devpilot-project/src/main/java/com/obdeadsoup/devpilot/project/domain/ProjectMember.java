package com.obdeadsoup.devpilot.project.domain;

public record ProjectMember(
        long id,
        long workspaceId,
        long projectId,
        long userId,
        ProjectRole role,
        long version
) {
}
