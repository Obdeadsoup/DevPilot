package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryBacklogQuery;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncBacklogQuery;
import java.time.LocalDateTime;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * GitHub 模块自己的 backlog 聚合查询。每类状态机单次 SQL 返回计数和最老时间，避免 Gauge scrape 访问数据库。
 */
@Mapper
public interface GitHubBacklogMapper {

    @Select("""
            SELECT
              SUM(processing_status='RECEIVED') AS receivedCount,
              SUM(processing_status='RETRY_WAIT' AND next_retry_at<=#{now}) AS retryWaitDueCount,
              SUM(processing_status='PROCESSING') AS processingCount,
              SUM(processing_status='PROCESSING' AND processing_started_at<=#{cutoff}) AS staleProcessingCount,
              SUM(processing_status='DEAD') AS openDeadCount,
              MIN(CASE WHEN processing_status='RECEIVED' THEN received_at
                       WHEN processing_status='RETRY_WAIT' AND next_retry_at<=#{now} THEN next_retry_at END) AS oldestReadyAt,
              MIN(CASE WHEN processing_status='PROCESSING' THEN processing_started_at END) AS oldestProcessingAt
            FROM dp_github_delivery
            """)
    GitHubDeliveryBacklogQuery delivery(
            @Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff);

    @Select("""
            SELECT
              SUM(r.status='PENDING') AS pendingCount,
              SUM(r.status='RETRY_WAIT' AND r.next_retry_at<=#{now}) AS retryWaitDueCount,
              SUM(r.status='RUNNING') AS runningCount,
              SUM(r.status='RUNNING' AND r.started_at<=#{cutoff}) AS staleRunningCount,
              SUM(r.status='DEAD' AND NOT EXISTS (
                    SELECT 1 FROM dp_github_sync_run replay
                    WHERE replay.replay_of_run_id=r.id
                      AND replay.status IN ('PENDING','RUNNING','RETRY_WAIT','SUCCEEDED')
              )) AS openDeadCount,
              MIN(CASE WHEN r.status='PENDING' THEN r.created_at
                       WHEN r.status='RETRY_WAIT' AND r.next_retry_at<=#{now} THEN r.next_retry_at END) AS oldestReadyAt,
              MIN(CASE WHEN r.status='RUNNING' THEN r.started_at END) AS oldestRunningAt
            FROM dp_github_sync_run r
            """)
    GitHubSyncBacklogQuery sync(
            @Param("now") LocalDateTime now, @Param("cutoff") LocalDateTime cutoff);
}
