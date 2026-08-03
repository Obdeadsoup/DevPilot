package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncCheckpointEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.Optional;

/**
 * 多资源 Checkpoint 持久化边界。整轮可靠边界使用 version 条件更新；Commit 另有页级 SHA 进度。
 * lastSuccessfulSyncAt 只允许在本地数据已保存且整轮成功时推进。
 */
@Mapper
public interface GitHubSyncCheckpointMapper {

    @Insert("""
            INSERT INTO dp_github_sync_checkpoint (
                repository_binding_id, resource_type, overlap_seconds
            ) VALUES (#{bindingId}, #{resourceType}, #{overlapSeconds})
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertResourceIfAbsent(
            @Param("bindingId") long bindingId,
            @Param("resourceType") String resourceType,
            @Param("overlapSeconds") long overlapSeconds
    );

    default int insertIfAbsent(long bindingId,long overlapSeconds){
        return insertResourceIfAbsent(bindingId,"COMMIT",overlapSeconds);
    }

    @Select("""
            SELECT id, repository_binding_id AS repositoryBindingId,
                   resource_type AS resourceType,
                   last_successful_sync_at AS lastSuccessfulSyncAt,
                   last_seen_commit_sha AS lastSeenCommitSha,
                   overlap_seconds AS overlapSeconds,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM dp_github_sync_checkpoint
            WHERE repository_binding_id = #{bindingId} AND resource_type = 'COMMIT'
            """)
    Optional<GitHubSyncCheckpointEntity> findCommitCheckpoint(@Param("bindingId") long bindingId);

    @Select("""
            SELECT id, repository_binding_id AS repositoryBindingId, resource_type AS resourceType,
                   last_successful_sync_at AS lastSuccessfulSyncAt,
                   last_seen_commit_sha AS lastSeenCommitSha, overlap_seconds AS overlapSeconds,
                   created_at AS createdAt, updated_at AS updatedAt, version
            FROM dp_github_sync_checkpoint
            WHERE repository_binding_id=#{bindingId} AND resource_type=#{resourceType}
            """)
    Optional<GitHubSyncCheckpointEntity> findCheckpoint(@Param("bindingId") long bindingId,
                                                        @Param("resourceType") String resourceType);

    @Update("""
            UPDATE dp_github_sync_checkpoint
            SET last_seen_commit_sha = #{lastSeenCommitSha},
                version = version + 1
            WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int updatePageProgress(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("lastSeenCommitSha") String lastSeenCommitSha
    );

    @Update("""
            UPDATE dp_github_sync_checkpoint
            SET last_successful_sync_at = CASE
                    WHEN last_successful_sync_at IS NULL THEN #{successfulBoundary}
                    WHEN #{successfulBoundary} IS NULL THEN last_successful_sync_at
                    ELSE GREATEST(last_successful_sync_at, #{successfulBoundary})
                END,
                last_seen_commit_sha = COALESCE(#{lastSeenCommitSha}, last_seen_commit_sha),
                overlap_seconds = #{overlapSeconds},
                version = version + 1
            WHERE id = #{id} AND version = #{expectedVersion}
            """)
    int complete(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("successfulBoundary") LocalDateTime successfulBoundary,
            @Param("lastSeenCommitSha") String lastSeenCommitSha,
            @Param("overlapSeconds") long overlapSeconds
    );
}
