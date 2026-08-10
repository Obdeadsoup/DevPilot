package com.obdeadsoup.devpilot.audit.persistence.mapper;

import com.obdeadsoup.devpilot.audit.domain.DeadGitHubSyncRunResponse;
import com.obdeadsoup.devpilot.audit.domain.DeadOutboxEventResponse;
import com.obdeadsoup.devpilot.audit.persistence.entity.GitHubSyncReplaySource;
import com.obdeadsoup.devpilot.audit.persistence.entity.OutboxReplaySource;
import com.obdeadsoup.devpilot.audit.persistence.entity.ReplayInsert;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;
import java.util.Optional;

/**
 * DEAD 运维查询 Mapper。所有查询都在 SQL 中绑定 Workspace/Project/Binding scope，避免先取裸 ID 再做内存鉴权。
 */
@Mapper
public interface DeadLetterMapper {

    String OUTBOX_SCOPE = " FROM dp_outbox_event e JOIN dp_task t ON e.aggregate_type='TASK' AND t.id=e.aggregate_id ";

    @Select("""
            SELECT e.id,e.event_type AS eventType,e.aggregate_type AS aggregateType,e.aggregate_id AS aggregateId,
                   e.processing_status AS status,e.retry_count AS retryCount,e.occurred_at AS occurredAt,
                   e.last_error_code AS lastErrorCode,e.last_error_message AS lastErrorMessage,
                   (SELECT COUNT(*) FROM dp_outbox_event r WHERE r.replay_of_event_id=e.id) AS replayCount,
                   e.replay_of_event_id AS replayOfEventId,e.processed_at AS processedAt,e.version
            """ + OUTBOX_SCOPE + """
            WHERE t.workspace_id=#{workspaceId} AND t.project_id=#{projectId} AND e.processing_status='DEAD'
            ORDER BY e.occurred_at DESC,e.id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<DeadOutboxEventResponse> findDeadOutbox(@Param("workspaceId") long workspaceId,
                                                 @Param("projectId") long projectId,
                                                 @Param("offset") long offset,
                                                 @Param("limit") int limit);

    @Select("SELECT COUNT(*) " + OUTBOX_SCOPE +
            " WHERE t.workspace_id=#{workspaceId} AND t.project_id=#{projectId} AND e.processing_status='DEAD'")
    long countDeadOutbox(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId);

    @Select("""
            SELECT e.id,e.event_type AS eventType,e.aggregate_type AS aggregateType,e.aggregate_id AS aggregateId,
                   e.processing_status AS status,e.retry_count AS retryCount,e.occurred_at AS occurredAt,
                   e.last_error_code AS lastErrorCode,e.last_error_message AS lastErrorMessage,
                   (SELECT COUNT(*) FROM dp_outbox_event r WHERE r.replay_of_event_id=e.id) AS replayCount,
                   e.replay_of_event_id AS replayOfEventId,e.processed_at AS processedAt,e.version
            """ + OUTBOX_SCOPE + """
            WHERE t.workspace_id=#{workspaceId} AND t.project_id=#{projectId} AND e.id=#{eventId}
            """)
    Optional<DeadOutboxEventResponse> findOutboxInScope(@Param("workspaceId") long workspaceId,
                                                        @Param("projectId") long projectId,
                                                        @Param("eventId") long eventId);

    @Select("""
            SELECT e.id,e.event_type AS eventType,e.aggregate_type AS aggregateType,e.aggregate_id AS aggregateId,
                   e.schema_version AS schemaVersion,CAST(e.payload_json AS CHAR) AS payloadJson,
                   e.processing_status AS status,e.retry_count AS retryCount,e.version
            """ + OUTBOX_SCOPE + """
            WHERE t.workspace_id=#{workspaceId} AND t.project_id=#{projectId} AND e.id=#{eventId} FOR UPDATE
            """)
    Optional<OutboxReplaySource> lockOutboxInScope(@Param("workspaceId") long workspaceId,
                                                   @Param("projectId") long projectId,
                                                   @Param("eventId") long eventId);

    @Select("SELECT COUNT(*) FROM dp_outbox_event WHERE id=#{eventId}")
    int existsOutbox(@Param("eventId") long eventId);

    @Select("SELECT COALESCE(MAX(replay_sequence),0) FROM dp_outbox_event WHERE replay_of_event_id=#{eventId}")
    int maxOutboxReplaySequence(@Param("eventId") long eventId);

    @Select("SELECT id FROM dp_outbox_event WHERE replay_of_event_id=#{eventId} AND processing_status IN ('PENDING','PROCESSING','RETRY_WAIT') LIMIT 1")
    Optional<Long> findOpenOutboxReplay(@Param("eventId") long eventId);

    @Insert("""
            INSERT INTO dp_outbox_event(event_key,aggregate_type,aggregate_id,event_type,schema_version,payload_json,
              processing_status,retry_count,occurred_at,version,replay_of_event_id,replay_sequence,replay_requested_by,replay_reason)
            VALUES(#{eventKey},#{source.aggregateType},#{source.aggregateId},#{source.eventType},#{source.schemaVersion},
              CAST(#{source.payloadJson} AS JSON),'PENDING',0,CURRENT_TIMESTAMP(6),0,#{source.id},#{sequence},#{actorUserId},#{reason})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "replay.id")
    int insertOutboxReplay(@Param("replay") ReplayInsert replay, @Param("source") OutboxReplaySource source,
                           @Param("eventKey") String eventKey, @Param("sequence") int sequence,
                           @Param("actorUserId") long actorUserId, @Param("reason") String reason);

    @Select("""
            SELECT r.id,r.repository_binding_id AS bindingId,r.resource_type AS resourceType,r.trigger_type AS triggerType,
                   r.status,r.attempt_count AS attemptCount,r.completed_at AS completedAt,
                   r.last_error_code AS lastErrorCode,r.last_error_message AS lastErrorMessage,
                   (SELECT COUNT(*) FROM dp_github_sync_run x WHERE x.replay_of_run_id=r.id) AS replayCount,
                   r.replay_of_run_id AS replayOfRunId,r.version
            FROM dp_github_sync_run r JOIN dp_github_repository g ON g.id=r.repository_binding_id
            WHERE g.workspace_id=#{workspaceId} AND g.project_id=#{projectId} AND g.id=#{bindingId}
              AND r.status='DEAD' ORDER BY r.created_at DESC,r.id DESC LIMIT #{limit} OFFSET #{offset}
            """)
    List<DeadGitHubSyncRunResponse> findDeadSyncRuns(@Param("workspaceId") long workspaceId,
                                                     @Param("projectId") long projectId,
                                                     @Param("bindingId") long bindingId,
                                                     @Param("offset") long offset,
                                                     @Param("limit") int limit);

    @Select("""
            SELECT COUNT(*) FROM dp_github_sync_run r JOIN dp_github_repository g ON g.id=r.repository_binding_id
            WHERE g.workspace_id=#{workspaceId} AND g.project_id=#{projectId} AND g.id=#{bindingId} AND r.status='DEAD'
            """)
    long countDeadSyncRuns(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                           @Param("bindingId") long bindingId);

    @Select("""
            SELECT r.id,r.repository_binding_id AS bindingId,r.resource_type AS resourceType,r.status,
                   r.attempt_count AS attemptCount,r.version
            FROM dp_github_sync_run r JOIN dp_github_repository g ON g.id=r.repository_binding_id
            WHERE g.workspace_id=#{workspaceId} AND g.project_id=#{projectId} AND g.id=#{bindingId} AND r.id=#{runId}
            FOR UPDATE
            """)
    Optional<GitHubSyncReplaySource> lockSyncRunInScope(@Param("workspaceId") long workspaceId,
                                                        @Param("projectId") long projectId,
                                                        @Param("bindingId") long bindingId,
                                                        @Param("runId") long runId);

    @Select("SELECT COUNT(*) FROM dp_github_sync_run WHERE id=#{runId}")
    int existsSyncRun(@Param("runId") long runId);

    @Select("SELECT COALESCE(MAX(replay_sequence),0) FROM dp_github_sync_run WHERE replay_of_run_id=#{runId}")
    int maxSyncReplaySequence(@Param("runId") long runId);

    @Select("SELECT id FROM dp_github_sync_run WHERE repository_binding_id=#{bindingId} AND resource_type=#{resourceType} AND status IN ('PENDING','RUNNING','RETRY_WAIT') LIMIT 1")
    Optional<Long> findOpenSyncRun(@Param("bindingId") long bindingId, @Param("resourceType") String resourceType);

    @Insert("""
            INSERT INTO dp_github_sync_run(repository_binding_id,resource_type,trigger_type,status,attempt_count,
              requested_by,version,replay_of_run_id,replay_sequence,replay_requested_by,replay_reason)
            VALUES(#{source.bindingId},#{source.resourceType},'MANUAL_REPLAY','PENDING',0,#{actorUserId},0,
              #{source.id},#{sequence},#{actorUserId},#{reason})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "replay.id")
    int insertSyncReplay(@Param("replay") ReplayInsert replay, @Param("source") GitHubSyncReplaySource source,
                         @Param("sequence") int sequence, @Param("actorUserId") long actorUserId,
                         @Param("reason") String reason);
}
