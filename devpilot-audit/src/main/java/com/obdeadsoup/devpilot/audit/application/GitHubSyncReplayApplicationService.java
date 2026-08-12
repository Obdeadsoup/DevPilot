package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.audit.error.AuditErrorCode;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

import java.util.Map;

/** GitHub Sync 人工 Replay 编排；客户端不能提交 since/cursor，Checkpoint 与 overlap 语义由原 Worker 保持。 */
@Service
public class GitHubSyncReplayApplicationService {
    private final CurrentUserProvider users; private final ReplayAuthorizationService authorization;
    private final ReplayReasonValidator reasons; private final GitHubSyncReplayTransactionService transaction;
    private final AuditRecorder audit; private final AuditCommandFactory commands;
    private final AuditReplayMetrics metrics;
    public GitHubSyncReplayApplicationService(CurrentUserProvider users,ReplayAuthorizationService authorization,
                                              ReplayReasonValidator reasons,GitHubSyncReplayTransactionService transaction,
                                              AuditRecorder audit,AuditCommandFactory commands,
                                              AuditReplayMetrics metrics){
        this.users=users;this.authorization=authorization;this.reasons=reasons;this.transaction=transaction;
        this.audit=audit;this.commands=commands;this.metrics=metrics;}
    public ReplayReceiptResponse replay(long workspaceId,long projectId,long bindingId,long runId,ReplayRequest request){
        long userId=users.requireUserId(); String reason=null;
        try{
            authorization.requireSyncReplay(userId,workspaceId,projectId);
            reason=reasons.validate(request.reason());
            ReplayReceiptResponse receipt = transaction.replay(
                    userId,workspaceId,projectId,bindingId,runId,request.expectedVersion(),reason);
            metrics.record("github_sync", "created");
            return receipt;
        }catch(BusinessException exception){
            recordRejected(userId,workspaceId,projectId,runId,reason,exception.errorCode().code(),
                    exception.errorCode()==AuditErrorCode.AUDIT_ACCESS_DENIED?AuditResult.DENIED:AuditResult.FAILURE);
            metrics.record("github_sync",
                    exception.errorCode()==AuditErrorCode.AUDIT_ACCESS_DENIED ? "denied" : "failed");
            throw exception;
        }catch(DataIntegrityViolationException exception){
            var conflict=new BusinessException(AuditErrorCode.SYNC_RUN_ALREADY_HAS_OPEN_REPLAY);
            recordRejected(userId,workspaceId,projectId,runId,reason,conflict.errorCode().code(),AuditResult.FAILURE);
            metrics.record("github_sync", "failed");
            throw conflict;
        }
    }
    private void recordRejected(long userId,long workspaceId,long projectId,long runId,String reason,String code,AuditResult result){
        audit.recordFailure(commands.user(userId,workspaceId,projectId,AuditActionType.GITHUB_SYNC_REPLAY_REJECTED,
                AuditResourceType.GITHUB_SYNC_RUN,runId,result,reason,code,Map.of()));
    }
}
