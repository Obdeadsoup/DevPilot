package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.domain.*;
import com.obdeadsoup.devpilot.audit.error.AuditErrorCode;
import com.obdeadsoup.devpilot.audit.persistence.entity.ReplayInsert;
import com.obdeadsoup.devpilot.audit.persistence.mapper.DeadLetterMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.outbox.event.OutboxStoredSignal;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
class OutboxReplayTransactionService {
    private final DeadLetterMapper mapper;
    private final ReplayableOutboxEventPolicy policy;
    private final AuditRecorder audit;
    private final AuditCommandFactory commands;
    private final ReplaySequence sequences;
    private final ApplicationEventPublisher events;

    OutboxReplayTransactionService(DeadLetterMapper mapper, ReplayableOutboxEventPolicy policy,
                                   AuditRecorder audit, AuditCommandFactory commands, ReplaySequence sequences,
                                   ApplicationEventPublisher events) {
        this.mapper=mapper; this.policy=policy; this.audit=audit; this.commands=commands; this.sequences=sequences; this.events=events;
    }

    /** 原 DEAD 是不可逆故障证据；人工 Replay 复制原事件为新 PENDING 行，并与 SUCCESS Audit 原子提交。 */
    @Transactional
    public ReplayReceiptResponse replay(long userId,long workspaceId,long projectId,long eventId,
                                        long expectedVersion,String reason) {
        var source=mapper.lockOutboxInScope(workspaceId,projectId,eventId).orElseThrow(() ->
                new BusinessException(mapper.existsOutbox(eventId)>0
                        ? AuditErrorCode.DEAD_EVENT_SCOPE_MISMATCH : AuditErrorCode.DEAD_EVENT_NOT_FOUND));
        policy.requireReplayable(source.status(),source.eventType());
        if(source.version()!=expectedVersion) throw new BusinessException(AuditErrorCode.DEAD_EVENT_VERSION_CONFLICT);
        if(mapper.findOpenOutboxReplay(eventId).isPresent())
            throw new BusinessException(AuditErrorCode.DEAD_EVENT_ALREADY_HAS_OPEN_REPLAY);
        int sequence=sequences.next(mapper.maxOutboxReplaySequence(eventId));
        audit.record(commands.user(userId,workspaceId,projectId,AuditActionType.OUTBOX_REPLAY_REQUESTED,
                AuditResourceType.OUTBOX_EVENT,eventId,AuditResult.SUCCESS,reason,null,
                Map.of("originalStatus",source.status(),"originalAttemptCount",source.retryCount(),
                        "eventType",source.eventType(),"replaySequence",sequence)));
        ReplayInsert replay=new ReplayInsert();
        mapper.insertOutboxReplay(replay,source,"manual-replay:outbox:"+eventId+":"+sequence,
                sequence,userId,reason);
        audit.record(commands.user(userId,workspaceId,projectId,AuditActionType.OUTBOX_REPLAY_CREATED,
                AuditResourceType.OUTBOX_EVENT,eventId,AuditResult.SUCCESS,reason,null,
                Map.of("newReplayId",replay.getId(),"eventType",source.eventType(),"replaySequence",sequence)));
        events.publishEvent(new OutboxStoredSignal(replay.getId()));
        return new ReplayReceiptResponse(replay.getId(),"PENDING");
    }
}
