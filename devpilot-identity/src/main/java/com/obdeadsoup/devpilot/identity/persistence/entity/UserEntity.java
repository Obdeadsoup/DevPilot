package com.obdeadsoup.devpilot.identity.persistence.entity;

import java.time.LocalDateTime;

public record UserEntity(
        long id,
        String username,
        String email,
        String displayName,
        String passwordHash,
        String status,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version,
        boolean deleted
) {
}
