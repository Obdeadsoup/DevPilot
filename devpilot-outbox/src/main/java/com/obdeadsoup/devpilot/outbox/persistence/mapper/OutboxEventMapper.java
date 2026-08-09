package com.obdeadsoup.devpilot.outbox.persistence.mapper;

import com.obdeadsoup.devpilot.outbox.persistence.entity.OutboxEventEntity;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/**
 * Outbox 状态机 Mapper。扫描只发现候选；claim 和所有终态更新都以 status + version 条件仲裁所有权。
 */
@Mapper
public interface OutboxEventMapper {

    String COLUMNS = """
            id, event_key AS eventKey, aggregate_type AS aggregateType, aggregate_id AS aggregateId,
            event_type AS eventType, schema_version AS schemaVersion, CAST(payload_json AS CHAR) AS payloadJson,
            processing_status AS processingStatus, retry_count AS retryCount, next_retry_at AS nextRetryAt,
            processing_started_at AS processingStartedAt, processed_at AS processedAt,
            last_error_code AS lastErrorCode, last_error_message AS lastErrorMessage,
            occurred_at AS occurredAt, created_at AS createdAt, updated_at AS updatedAt, version
            """;

    @Insert("""
            INSERT INTO dp_outbox_event (
                event_key, aggregate_type, aggregate_id, event_type, schema_version, payload_json,
                processing_status, retry_count, occurred_at, version
            ) VALUES (
                #{event.eventKey}, #{event.aggregateType}, #{event.aggregateId}, #{event.eventType},
                #{event.schemaVersion}, CAST(#{event.payloadJson} AS JSON), 'PENDING', 0, #{event.occurredAt}, 0
            )
            """)
    @Options(useGeneratedKeys = true, keyProperty = "event.id")
    int insert(@Param("event") OutboxEventEntity event);

    @Select("SELECT " + COLUMNS + " FROM dp_outbox_event WHERE id=#{id}")
    Optional<OutboxEventEntity> findById(@Param("id") long id);

    @Select("SELECT " + COLUMNS + " FROM dp_outbox_event WHERE event_key=#{eventKey} FOR UPDATE")
    Optional<OutboxEventEntity> findByEventKeyForUpdate(@Param("eventKey") String eventKey);

    @Select("""
            SELECT id FROM dp_outbox_event
            WHERE processing_status='PENDING'
            ORDER BY occurred_at, id LIMIT #{limit}
            """)
    List<Long> findPendingIds(@Param("limit") int limit);

    @Select("""
            SELECT id FROM dp_outbox_event
            WHERE processing_status='RETRY_WAIT' AND next_retry_at<=#{now}
            ORDER BY next_retry_at, id LIMIT #{limit}
            """)
    List<Long> findDueRetryIds(@Param("now") LocalDateTime now, @Param("limit") int limit);

    @Select("""
            SELECT 
            """ + COLUMNS + """
            FROM dp_outbox_event
            WHERE processing_status='PROCESSING' AND processing_started_at<=#{cutoff}
            ORDER BY processing_started_at, id LIMIT #{limit}
            """)
    List<OutboxEventEntity> findStaleProcessing(
            @Param("cutoff") LocalDateTime cutoff, @Param("limit") int limit);

    @Update("""
            UPDATE dp_outbox_event
            SET processing_status='PROCESSING', processing_started_at=#{now}, next_retry_at=NULL,
                last_error_code=NULL, last_error_message=NULL, version=version+1
            WHERE id=#{id} AND version=#{expectedVersion}
              AND (processing_status='PENDING'
                   OR (processing_status='RETRY_WAIT' AND next_retry_at<=#{now}))
            """)
    int claim(@Param("id") long id, @Param("expectedVersion") long expectedVersion,
              @Param("now") LocalDateTime now);

    @Update("""
            UPDATE dp_outbox_event
            SET processing_status='PROCESSED', processed_at=#{now}, processing_started_at=NULL,
                next_retry_at=NULL, last_error_code=NULL, last_error_message=NULL, version=version+1
            WHERE id=#{id} AND processing_status='PROCESSING' AND version=#{expectedVersion}
            """)
    int markProcessed(@Param("id") long id, @Param("expectedVersion") long expectedVersion,
                      @Param("now") LocalDateTime now);

    @Update("""
            UPDATE dp_outbox_event
            SET processing_status=#{targetStatus}, retry_count=#{retryCount}, next_retry_at=#{nextRetryAt},
                processing_started_at=NULL, last_error_code=#{errorCode}, last_error_message=#{errorMessage},
                version=version+1
            WHERE id=#{id} AND processing_status='PROCESSING' AND version=#{expectedVersion}
            """)
    int markFailure(@Param("id") long id, @Param("expectedVersion") long expectedVersion,
                    @Param("targetStatus") String targetStatus, @Param("retryCount") int retryCount,
                    @Param("nextRetryAt") LocalDateTime nextRetryAt, @Param("errorCode") String errorCode,
                    @Param("errorMessage") String errorMessage);

    @Update("""
            UPDATE dp_outbox_event
            SET processing_status=#{targetStatus}, retry_count=#{retryCount}, next_retry_at=#{nextRetryAt},
                processing_started_at=NULL, last_error_code='PROCESSING_TIMEOUT',
                last_error_message='Outbox processing timed out', version=version+1
            WHERE id=#{id} AND processing_status='PROCESSING' AND version=#{expectedVersion}
              AND processing_started_at<=#{cutoff}
            """)
    int recoverStale(@Param("id") long id, @Param("expectedVersion") long expectedVersion,
                     @Param("cutoff") LocalDateTime cutoff, @Param("targetStatus") String targetStatus,
                     @Param("retryCount") int retryCount, @Param("nextRetryAt") LocalDateTime nextRetryAt);
}
