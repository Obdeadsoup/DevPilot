package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Sync Run 状态机的复杂 Mapper。扫描只返回候选，真正互斥由 RUNNING 的 version 条件 claim 完成；
 * 活动 Run 唯一索引则防止同一 Binding 同时创建多个开放任务。
 */
@Mapper
public interface GitHubSyncRunMapper {

    String COLUMNS = """
            id, repository_binding_id AS repositoryBindingId, resource_type AS resourceType,
            trigger_type AS triggerType, status, attempt_count AS attemptCount,
            next_retry_at AS nextRetryAt, started_at AS startedAt, completed_at AS completedAt,
            last_error_code AS lastErrorCode, last_error_message AS lastErrorMessage,
            requested_by AS requestedBy, created_at AS createdAt, updated_at AS updatedAt, version
            """;

    @Insert("""
            INSERT INTO dp_github_sync_run (
                repository_binding_id, resource_type, trigger_type, status, requested_by
            ) VALUES (#{bindingId}, #{resourceType}, #{triggerType}, 'PENDING', #{requestedBy})
            """)
    int insertPendingResource(
            @Param("bindingId") long bindingId,
            @Param("resourceType") String resourceType,
            @Param("triggerType") String triggerType,
            @Param("requestedBy") Long requestedBy
    );

    default int insertPending(long bindingId,String triggerType,Long requestedBy){
        return insertPendingResource(bindingId,"COMMIT",triggerType,requestedBy);
    }

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_sync_run
            WHERE repository_binding_id = #{bindingId}
              AND resource_type = 'COMMIT'
              AND status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')
            ORDER BY id DESC LIMIT 1
            """)
    Optional<GitHubSyncRunEntity> findOpenCommitRun(@Param("bindingId") long bindingId);

    @Select("SELECT " + COLUMNS + " FROM dp_github_sync_run WHERE repository_binding_id=#{bindingId} AND resource_type=#{resourceType} AND status IN ('PENDING','RUNNING','RETRY_WAIT') ORDER BY id DESC LIMIT 1")
    Optional<GitHubSyncRunEntity> findOpenRun(@Param("bindingId") long bindingId,
                                              @Param("resourceType") String resourceType);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_sync_run
            WHERE id = #{id}
            """)
    Optional<GitHubSyncRunEntity> findById(@Param("id") long id);

    @Select("""
            SELECT id
            FROM dp_github_sync_run
            WHERE status = 'PENDING'
               OR (status = 'RETRY_WAIT' AND next_retry_at <= #{now})
            ORDER BY CASE WHEN status = 'RETRY_WAIT' THEN next_retry_at ELSE created_at END, id
            LIMIT #{limit}
            """)
    List<Long> findRunnableCandidateIds(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_sync_run
            WHERE status = 'RUNNING' AND started_at <= #{cutoff}
            ORDER BY started_at, id
            LIMIT #{limit}
            """)
    List<GitHubSyncRunEntity> findStaleRunningCandidates(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit
    );

    @Update("""
            UPDATE dp_github_sync_run
            SET status = 'RUNNING', attempt_count = attempt_count + 1,
                started_at = #{startedAt}, completed_at = NULL, next_retry_at = NULL,
                last_error_code = NULL, last_error_message = NULL, version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
              AND (status = 'PENDING'
                   OR (status = 'RETRY_WAIT' AND next_retry_at <= #{startedAt}))
            """)
    int claim(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("startedAt") LocalDateTime startedAt
    );

    @Update("""
            UPDATE dp_github_sync_run
            SET status = 'RETRY_WAIT', next_retry_at = #{nextRetryAt},
                started_at = NULL, completed_at = NULL,
                last_error_code = #{errorCode}, last_error_message = #{errorMessage},
                version = version + 1
            WHERE id = #{id} AND status = 'RUNNING' AND version = #{expectedVersion}
            """)
    int markRetryWait(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );

    @Update("""
            UPDATE dp_github_sync_run
            SET status = 'DEAD', next_retry_at = NULL, completed_at = #{completedAt},
                last_error_code = #{errorCode}, last_error_message = #{errorMessage},
                version = version + 1
            WHERE id = #{id} AND status = 'RUNNING' AND version = #{expectedVersion}
            """)
    int markDead(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("completedAt") LocalDateTime completedAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );

    @Update("""
            UPDATE dp_github_sync_run
            SET status = 'SUCCEEDED', next_retry_at = NULL, completed_at = #{completedAt},
                last_error_code = NULL, last_error_message = NULL, version = version + 1
            WHERE id = #{id} AND status = 'RUNNING' AND version = #{expectedVersion}
            """)
    int markSucceeded(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("completedAt") LocalDateTime completedAt
    );

    @Update("""
            UPDATE dp_github_sync_run
            SET status = 'RETRY_WAIT', next_retry_at = #{nextRetryAt}, started_at = NULL,
                completed_at = NULL, last_error_code = 'WORKER_TIMEOUT',
                last_error_message = 'GitHub synchronization worker timed out',
                version = version + 1
            WHERE id = #{id} AND status = 'RUNNING' AND version = #{expectedVersion}
              AND started_at <= #{cutoff}
            """)
    int recoverStaleToRetryWait(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("nextRetryAt") LocalDateTime nextRetryAt
    );

    @Update("""
            UPDATE dp_github_sync_run
            SET status = 'DEAD', next_retry_at = NULL, completed_at = #{completedAt},
                last_error_code = 'WORKER_TIMEOUT',
                last_error_message = 'GitHub synchronization worker timed out',
                version = version + 1
            WHERE id = #{id} AND status = 'RUNNING' AND version = #{expectedVersion}
              AND started_at <= #{cutoff}
            """)
    int recoverStaleToDead(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("completedAt") LocalDateTime completedAt
    );
}
