package com.obdeadsoup.devpilot.outbox.persistence.mapper;

import com.obdeadsoup.devpilot.outbox.persistence.entity.OutboxBacklogQuery;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** Outbox 模块内单次 backlog 聚合查询；open DEAD 排除已有成功或开放 Replay 的历史故障。 */
@Mapper
public interface OutboxBacklogMapper {

    @Select("""
            SELECT
              SUM(e.processing_status='PENDING') AS pendingCount,
              SUM(e.processing_status='RETRY_WAIT' AND e.next_retry_at<=#{now}) AS retryWaitDueCount,
              SUM(e.processing_status='PROCESSING') AS processingCount,
              SUM(e.processing_status='PROCESSING' AND e.processing_started_at<=#{cutoff}) AS staleProcessingCount,
              SUM(e.processing_status='DEAD' AND NOT EXISTS (
                    SELECT 1 FROM dp_outbox_event replay
                    WHERE replay.replay_of_event_id=e.id
                      AND replay.processing_status IN ('PENDING','PROCESSING','RETRY_WAIT','PROCESSED')
              )) AS openDeadCount,
              MIN(CASE WHEN e.processing_status='PENDING' THEN e.occurred_at
                       WHEN e.processing_status='RETRY_WAIT' AND e.next_retry_at<=#{now} THEN e.next_retry_at END) AS oldestReadyAt
            FROM dp_outbox_event e
            """)
    OutboxBacklogQuery snapshot(
            @Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff);
}
