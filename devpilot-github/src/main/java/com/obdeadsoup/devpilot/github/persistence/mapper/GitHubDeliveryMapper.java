package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Mapper
public interface GitHubDeliveryMapper {

    @Insert("""
            INSERT INTO dp_github_delivery (
                workspace_id, project_id, repository_id, github_delivery_id, event_type, action,
                signature_status, processing_status, payload_json, payload_sha256, received_at
            ) VALUES (
                #{workspaceId}, #{projectId}, #{repositoryId}, #{githubDeliveryId}, #{eventType}, #{action},
                'VALID', 'RECEIVED', CAST(#{payloadJson} AS JSON), #{payloadSha256}, #{receivedAt}
            )
            """)
    int insertReceived(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("repositoryId") long repositoryId,
            @Param("githubDeliveryId") String githubDeliveryId,
            @Param("eventType") String eventType,
            @Param("action") String action,
            @Param("payloadJson") String payloadJson,
            @Param("payloadSha256") String payloadSha256,
            @Param("receivedAt") LocalDateTime receivedAt
    );

    @Select("""
            SELECT id,
                   workspace_id AS workspaceId,
                   project_id AS projectId,
                   repository_id AS repositoryId,
                   github_delivery_id AS githubDeliveryId,
                   event_type AS eventType,
                   action,
                   processing_status AS processingStatus,
                   CAST(payload_json AS CHAR) AS payloadJson,
                   payload_sha256 AS payloadSha256,
                   retry_count AS retryCount,
                   next_retry_at AS nextRetryAt,
                   processing_started_at AS processingStartedAt,
                   last_error_code AS lastErrorCode,
                   last_error_message AS lastErrorMessage,
                   received_at AS receivedAt,
                   version
            FROM dp_github_delivery
            WHERE github_delivery_id = #{githubDeliveryId}
            """)
    Optional<GitHubDeliveryEntity> findByGitHubDeliveryId(@Param("githubDeliveryId") String githubDeliveryId);

    @Select("""
            SELECT id,
                   workspace_id AS workspaceId,
                   project_id AS projectId,
                   repository_id AS repositoryId,
                   github_delivery_id AS githubDeliveryId,
                   event_type AS eventType,
                   action,
                   processing_status AS processingStatus,
                   CAST(payload_json AS CHAR) AS payloadJson,
                   payload_sha256 AS payloadSha256,
                   retry_count AS retryCount,
                   next_retry_at AS nextRetryAt,
                   processing_started_at AS processingStartedAt,
                   last_error_code AS lastErrorCode,
                   last_error_message AS lastErrorMessage,
                   received_at AS receivedAt,
                   version
            FROM dp_github_delivery
            WHERE id = #{id}
            """)
    Optional<GitHubDeliveryEntity> findById(@Param("id") long id);

    @Update("""
            UPDATE dp_github_delivery
            SET processing_status = 'PROCESSING',
                processing_started_at = #{startedAt},
                next_retry_at = NULL,
                version = version + 1
            WHERE id = #{id}
              AND (
                processing_status = 'RECEIVED'
                OR (processing_status = 'RETRY_WAIT' AND next_retry_at <= #{startedAt})
              )
              AND version = #{version}
            """)
    int claim(
            @Param("id") long id,
            @Param("version") long version,
            @Param("startedAt") LocalDateTime startedAt
    );

    @Update("""
            UPDATE dp_github_delivery
            SET processing_status = 'SUCCEEDED',
                processed_at = #{processedAt},
                next_retry_at = NULL,
                last_error_code = NULL,
                last_error_message = NULL,
                version = version + 1
            WHERE id = #{id}
              AND processing_status = 'PROCESSING'
              AND version = #{version}
            """)
    int markSucceeded(
            @Param("id") long id,
            @Param("version") long version,
            @Param("processedAt") LocalDateTime processedAt
    );

    @Update("""
            UPDATE dp_github_delivery
            SET processing_status = 'RETRY_WAIT',
                retry_count = retry_count + 1,
                next_retry_at = #{nextRetryAt},
                last_error_code = #{errorCode},
                last_error_message = #{errorMessage},
                processing_started_at = NULL,
                processed_at = NULL,
                version = version + 1
            WHERE id = #{id}
              AND processing_status = 'PROCESSING'
              AND version = #{version}
            """)
    int markRetryWait(
            @Param("id") long id,
            @Param("version") long version,
            @Param("nextRetryAt") LocalDateTime nextRetryAt,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage
    );

    @Update("""
            UPDATE dp_github_delivery
            SET processing_status = 'DEAD',
                retry_count = retry_count + 1,
                next_retry_at = NULL,
                last_error_code = #{errorCode},
                last_error_message = #{errorMessage},
                processing_started_at = NULL,
                processed_at = #{processedAt},
                version = version + 1
            WHERE id = #{id}
              AND processing_status = 'PROCESSING'
              AND version = #{version}
            """)
    int markDead(
            @Param("id") long id,
            @Param("version") long version,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("processedAt") LocalDateTime processedAt
    );

    @Update("""
            UPDATE dp_github_delivery
            SET processing_status = 'RETRY_WAIT',
                retry_count = retry_count + 1,
                next_retry_at = #{nextRetryAt},
                last_error_code = 'WORKER_TIMEOUT',
                last_error_message = 'Delivery processing timed out',
                processing_started_at = NULL,
                processed_at = NULL,
                version = version + 1
            WHERE id = #{id}
              AND processing_status = 'PROCESSING'
              AND version = #{version}
              AND processing_started_at <= #{cutoff}
            """)
    int recoverStaleProcessingToRetryWait(
            @Param("id") long id,
            @Param("version") long version,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("nextRetryAt") LocalDateTime nextRetryAt
    );

    @Update("""
            UPDATE dp_github_delivery
            SET processing_status = 'DEAD',
                retry_count = retry_count + 1,
                next_retry_at = NULL,
                last_error_code = 'WORKER_TIMEOUT',
                last_error_message = 'Delivery processing timed out',
                processing_started_at = NULL,
                processed_at = #{processedAt},
                version = version + 1
            WHERE id = #{id}
              AND processing_status = 'PROCESSING'
              AND version = #{version}
              AND processing_started_at <= #{cutoff}
            """)
    int recoverStaleProcessingToDead(
            @Param("id") long id,
            @Param("version") long version,
            @Param("cutoff") LocalDateTime cutoff,
            @Param("processedAt") LocalDateTime processedAt
    );

    @Select("""
            SELECT id
            FROM dp_github_delivery
            WHERE processing_status = 'RECEIVED'
            ORDER BY received_at, id
            LIMIT #{limit}
            """)
    List<Long> findReceivedCandidateIds(@Param("limit") int limit);

    @Select("""
            SELECT id
            FROM dp_github_delivery
            WHERE processing_status = 'RETRY_WAIT'
              AND next_retry_at <= #{now}
            ORDER BY next_retry_at, id
            LIMIT #{limit}
            """)
    List<Long> findDueRetryCandidateIds(
            @Param("now") LocalDateTime now,
            @Param("limit") int limit
    );

    @Select("""
            SELECT id,
                   workspace_id AS workspaceId,
                   project_id AS projectId,
                   repository_id AS repositoryId,
                   github_delivery_id AS githubDeliveryId,
                   event_type AS eventType,
                   action,
                   processing_status AS processingStatus,
                   CAST(payload_json AS CHAR) AS payloadJson,
                   payload_sha256 AS payloadSha256,
                   retry_count AS retryCount,
                   next_retry_at AS nextRetryAt,
                   processing_started_at AS processingStartedAt,
                   last_error_code AS lastErrorCode,
                   last_error_message AS lastErrorMessage,
                   received_at AS receivedAt,
                   version
            FROM dp_github_delivery
            WHERE processing_status = 'PROCESSING'
              AND processing_started_at <= #{cutoff}
            ORDER BY processing_started_at, id
            LIMIT #{limit}
            """)
    List<GitHubDeliveryEntity> findStaleProcessingCandidates(
            @Param("cutoff") LocalDateTime cutoff,
            @Param("limit") int limit
    );
}
