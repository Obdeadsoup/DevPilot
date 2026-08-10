package com.obdeadsoup.devpilot.audit.application;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.audit.error.AuditErrorCode;
import com.obdeadsoup.devpilot.audit.persistence.entity.AuditLogEntity;
import com.obdeadsoup.devpilot.audit.persistence.mapper.AuditLogMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/** Audit 管理查询服务；权限与 SQL 双重限定 scope，Project Admin 必须显式提供 projectId。 */
@Service
public class AuditQueryService {
    private final CurrentUserProvider users;private final ReplayAuthorizationService authorization;
    private final AuditLogMapper mapper;private final ObjectMapper objectMapper;
    public AuditQueryService(CurrentUserProvider users,ReplayAuthorizationService authorization,
                             AuditLogMapper mapper,ObjectMapper objectMapper){
        this.users=users;this.authorization=authorization;this.mapper=mapper;this.objectMapper=objectMapper;}

    @Transactional(readOnly=true)
    public PageResponse<AuditRecordResponse> list(long workspaceId,Long projectId,Long actorUserId,
            AuditActionType actionType,AuditResourceType resourceType,AuditResult result,
            LocalDateTime occurredFrom,LocalDateTime occurredTo,int page,int size){
        authorization.requireAuditRead(users.requireUserId(),workspaceId,projectId);
        int safePage=Math.max(page,1),safeSize=Math.min(Math.max(size,1),100);
        var rows=mapper.findPage(workspaceId,projectId,actorUserId,name(actionType),name(resourceType),name(result),
                occurredFrom,occurredTo,(long)(safePage-1)*safeSize,safeSize).stream().map(this::response).toList();
        long total=mapper.count(workspaceId,projectId,actorUserId,name(actionType),name(resourceType),name(result),
                occurredFrom,occurredTo);
        return new PageResponse<>(rows,total,safePage,safeSize);
    }

    @Transactional(readOnly=true)
    public AuditRecordResponse detail(long workspaceId,long auditId,Long projectId){
        authorization.requireAuditRead(users.requireUserId(),workspaceId,projectId);
        return mapper.findByScope(workspaceId,projectId,auditId).map(this::response)
                .orElseThrow(()->new BusinessException(AuditErrorCode.AUDIT_NOT_FOUND));
    }

    private String name(Enum<?> value){return value==null?null:value.name();}
    private AuditRecordResponse response(AuditLogEntity row){
        return new AuditRecordResponse(row.id(),AuditActorType.valueOf(row.actorType()),row.actorUserId(),
                row.workspaceId(),row.projectId(),AuditActionType.valueOf(row.actionType()),
                AuditResourceType.valueOf(row.resourceType()),row.resourceId(),AuditResult.valueOf(row.result()),
                row.reason(),row.errorCode(),row.requestId(),row.correlationId(),metadata(row.metadataJson()),
                row.occurredAt(),row.createdAt());
    }
    private Map<String,Object> metadata(String json){
        if(json==null||json.isBlank())return Map.of();
        try{return objectMapper.readValue(json,new TypeReference<>(){});}
        catch(Exception exception){throw new IllegalStateException("Stored audit metadata is invalid",exception);}
    }
}
