package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.audit.error.AuditErrorCode;
import com.obdeadsoup.devpilot.audit.event.GitHubSyncReplayCreatedSignal;
import com.obdeadsoup.devpilot.audit.persistence.entity.ReplayInsert;
import com.obdeadsoup.devpilot.audit.persistence.mapper.DeadLetterMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
class GitHubSyncReplayTransactionService {
    private final DeadLetterMapper mapper; private final AuditRecorder audit;
    private final AuditCommandFactory commands; private final ApplicationEventPublisher events;
    private final ReplaySequence sequences;
    GitHubSyncReplayTransactionService(DeadLetterMapper mapper,AuditRecorder audit,
                                       AuditCommandFactory commands,ReplaySequence sequences,ApplicationEventPublisher events){
        this.mapper=mapper;this.audit=audit;this.commands=commands;this.sequences=sequences;this.events=events;}

    /** 创建 MANUAL_REPLAY Run，但不写 Checkpoint；原 Worker 仍从最后可靠 Checkpoint 加 overlapWindow 执行。 */
    @Transactional
    public ReplayReceiptResponse replay(long userId,long workspaceId,long projectId,long bindingId,long runId,
                                        long expectedVersion,String reason){
        var source=mapper.lockSyncRunInScope(workspaceId,projectId,bindingId,runId)
                .orElseThrow(()->new BusinessException(AuditErrorCode.SYNC_RUN_NOT_FOUND));
        if(!"DEAD".equals(source.status())) throw new BusinessException(AuditErrorCode.SYNC_RUN_NOT_DEAD);
        if(source.version()!=expectedVersion) throw new BusinessException(AuditErrorCode.SYNC_RUN_VERSION_CONFLICT);
        if(mapper.findOpenSyncRun(bindingId,source.resourceType()).isPresent())
            throw new BusinessException(AuditErrorCode.SYNC_RUN_ALREADY_HAS_OPEN_REPLAY);
        int sequence=sequences.next(mapper.maxSyncReplaySequence(runId));
        audit.record(commands.user(userId,workspaceId,projectId,AuditActionType.GITHUB_SYNC_REPLAY_REQUESTED,
                AuditResourceType.GITHUB_SYNC_RUN,runId,AuditResult.SUCCESS,reason,null,
                Map.of("originalStatus",source.status(),"originalAttemptCount",source.attemptCount(),
                        "syncResourceType",source.resourceType(),"bindingId",bindingId,"replaySequence",sequence)));
        ReplayInsert replay=new ReplayInsert();
        mapper.insertSyncReplay(replay,source,sequence,userId,reason);
        audit.record(commands.user(userId,workspaceId,projectId,AuditActionType.GITHUB_SYNC_REPLAY_CREATED,
                AuditResourceType.GITHUB_SYNC_RUN,runId,AuditResult.SUCCESS,reason,null,
                Map.of("newReplayId",replay.getId(),"syncResourceType",source.resourceType(),
                        "bindingId",bindingId,"replaySequence",sequence)));
        events.publishEvent(new GitHubSyncReplayCreatedSignal(replay.getId()));
        return new ReplayReceiptResponse(replay.getId(),"PENDING");
    }
}
