package com.obdeadsoup.devpilot.audit.persistence.entity;

import java.time.LocalDateTime;

public record AuditLogEntity(
        long id, String actorType, Long actorUserId, Long workspaceId, Long projectId,
        String actionType, String resourceType, String resourceId, String result,
        String reason, String errorCode, String requestId, String correlationId,
        String metadataJson, LocalDateTime occurredAt, LocalDateTime createdAt) {
}
