package com.obdeadsoup.devpilot.identity.persistence.entity;

public record WorkspaceEntity(
        long id,
        Long ownerUserId,
        String status,
        long version,
        boolean deleted
) {
}
