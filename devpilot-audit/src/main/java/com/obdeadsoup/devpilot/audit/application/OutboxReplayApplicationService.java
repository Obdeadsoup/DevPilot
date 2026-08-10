package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.audit.error.AuditErrorCode;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Map;

/**
 * Outbox 人工 Replay 编排：鉴权、reason 校验后创建新事件；Replay 不等于自动 Retry，且仍依赖 Notification 唯一键幂等。
 */
@Service
public class OutboxReplayApplicationService {
    private final CurrentUserProvider users; private final ReplayAuthorizationService authorization;
    private final ReplayReasonValidator reasons; private final OutboxReplayTransactionService transaction;
    private final AuditRecorder audit; private final AuditCommandFactory commands;
    public OutboxReplayApplicationService(CurrentUserProvider users,ReplayAuthorizationService authorization,
                                          ReplayReasonValidator reasons,OutboxReplayTransactionService transaction,
                                          AuditRecorder audit,AuditCommandFactory commands){
        this.users=users;this.authorization=authorization;this.reasons=reasons;this.transaction=transaction;
        this.audit=audit;this.commands=commands;}

    public ReplayReceiptResponse replay(long workspaceId,long projectId,long eventId,ReplayRequest request){
        long userId=users.requireUserId(); String reason=null;
        try{
            authorization.requireOutboxAdministration(userId,workspaceId,projectId);
            reason=reasons.validate(request.reason());
            return transaction.replay(userId,workspaceId,projectId,eventId,request.expectedVersion(),reason);
        }catch(BusinessException exception){
            recordRejected(userId,workspaceId,projectId,eventId,reason,exception.errorCode().code(),
                    exception.errorCode()==AuditErrorCode.AUDIT_ACCESS_DENIED?AuditResult.DENIED:AuditResult.FAILURE);
            throw exception;
        }catch(DataIntegrityViolationException exception){
            var conflict=new BusinessException(AuditErrorCode.DEAD_EVENT_ALREADY_HAS_OPEN_REPLAY);
            recordRejected(userId,workspaceId,projectId,eventId,reason,conflict.errorCode().code(),AuditResult.FAILURE);
            throw conflict;
        }
    }
    private void recordRejected(long userId,long workspaceId,long projectId,long eventId,String reason,
                                String code,AuditResult result){
        audit.recordFailure(commands.user(userId,workspaceId,projectId,AuditActionType.OUTBOX_REPLAY_REJECTED,
                AuditResourceType.OUTBOX_EVENT,eventId,result,reason,code,Map.of()));
    }
}
