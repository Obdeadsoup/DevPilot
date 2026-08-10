package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.audit.error.AuditErrorCode;
import com.obdeadsoup.devpilot.audit.persistence.mapper.DeadLetterMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import org.springframework.stereotype.Service;

import java.util.Map;

/** DEAD 查询只返回诊断摘要，不返回 Outbox Payload；列表不逐条审计，详情与 Sync DEAD 查看会记录 Audit。 */
@Service
public class DeadLetterQueryService {
    private final CurrentUserProvider users;private final ReplayAuthorizationService authorization;
    private final DeadLetterMapper mapper;private final AuditRecorder audit;private final AuditCommandFactory commands;
    public DeadLetterQueryService(CurrentUserProvider users,ReplayAuthorizationService authorization,
                                  DeadLetterMapper mapper,AuditRecorder audit,AuditCommandFactory commands){
        this.users=users;this.authorization=authorization;this.mapper=mapper;this.audit=audit;this.commands=commands;}
    public PageResponse<DeadOutboxEventResponse> outbox(long workspaceId,long projectId,int page,int size){
        long userId=users.requireUserId();authorization.requireOutboxAdministration(userId,workspaceId,projectId);
        int safePage=Math.max(page,1),safeSize=Math.min(Math.max(size,1),100);
        return new PageResponse<>(mapper.findDeadOutbox(workspaceId,projectId,(long)(safePage-1)*safeSize,safeSize),
                mapper.countDeadOutbox(workspaceId,projectId),safePage,safeSize);
    }
    public DeadOutboxEventResponse outboxDetail(long workspaceId,long projectId,long eventId){
        long userId=users.requireUserId();authorization.requireOutboxAdministration(userId,workspaceId,projectId);
        var result=mapper.findOutboxInScope(workspaceId,projectId,eventId)
                .filter(event->"DEAD".equals(event.status()))
                .orElseThrow(()->new BusinessException(AuditErrorCode.DEAD_EVENT_NOT_FOUND));
        audit.recordStandalone(commands.user(userId,workspaceId,projectId,AuditActionType.OUTBOX_DEAD_VIEWED,
                AuditResourceType.OUTBOX_EVENT,eventId,AuditResult.SUCCESS,null,null,Map.of("eventType",result.eventType())));
        return result;
    }
    public PageResponse<DeadGitHubSyncRunResponse> syncRuns(long workspaceId,long projectId,long bindingId,int page,int size){
        long userId=users.requireUserId();authorization.requireSyncReplay(userId,workspaceId,projectId);
        int safePage=Math.max(page,1),safeSize=Math.min(Math.max(size,1),100);
        var response=new PageResponse<>(mapper.findDeadSyncRuns(workspaceId,projectId,bindingId,(long)(safePage-1)*safeSize,safeSize),
                mapper.countDeadSyncRuns(workspaceId,projectId,bindingId),safePage,safeSize);
        audit.recordStandalone(commands.user(userId,workspaceId,projectId,AuditActionType.GITHUB_SYNC_DEAD_VIEWED,
                AuditResourceType.GITHUB_REPOSITORY_BINDING,bindingId,AuditResult.SUCCESS,null,null,Map.of("bindingId",bindingId)));
        return response;
    }
}
