package com.obdeadsoup.devpilot.identity.persistence.entity;

import java.time.LocalDateTime;

public record WorkspaceMemberEntity(
        long id,
        long workspaceId,
        long userId,
        String role,
        String status,
        long invitedBy,
        LocalDateTime joinedAt,
        long version
) {
}
