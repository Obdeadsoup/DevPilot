package com.obdeadsoup.devpilot.audit.domain;

import java.time.LocalDateTime;
import java.util.Map;

public record AuditRecordCommand(
        AuditActorType actorType,
        Long actorUserId,
        Long workspaceId,
        Long projectId,
        AuditActionType actionType,
        AuditResourceType resourceType,
        String resourceId,
        AuditResult result,
        String reason,
        String errorCode,
        String requestId,
        String correlationId,
        Map<String, Object> metadata,
        LocalDateTime occurredAt) {
}
