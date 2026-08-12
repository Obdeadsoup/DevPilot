package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdAccessor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.Map;

@Component
class AuditCommandFactory {
    private final CorrelationIdAccessor correlationIds;

    AuditCommandFactory(CorrelationIdAccessor correlationIds) {
        this.correlationIds = correlationIds;
    }

    AuditRecordCommand user(long userId, long workspaceId, long projectId, AuditActionType action,
                            AuditResourceType resourceType, long resourceId, AuditResult result,
                            String reason, String errorCode, Map<String, Object> metadata) {
        return new AuditRecordCommand(AuditActorType.USER, userId, workspaceId, projectId, action,
                resourceType, Long.toString(resourceId), result, reason, errorCode,
                null, correlationIds.current().orElse(null), metadata, LocalDateTime.now());
    }
}
