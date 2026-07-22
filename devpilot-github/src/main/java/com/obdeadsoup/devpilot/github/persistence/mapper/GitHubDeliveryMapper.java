package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
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
                last_error_code = NULL,
                last_error_message = NULL,
                version = version + 1
            WHERE id = #{id}
              AND processing_status IN ('RECEIVED', 'RETRY_WAIT')
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
            SET processing_status = 'FAILED',
                last_error_code = #{errorCode},
                last_error_message = #{errorMessage},
                processed_at = #{processedAt},
                version = version + 1
            WHERE id = #{id} AND processing_status = 'PROCESSING'
            """)
    int markFailed(
            @Param("id") long id,
            @Param("errorCode") String errorCode,
            @Param("errorMessage") String errorMessage,
            @Param("processedAt") LocalDateTime processedAt
    );
}
