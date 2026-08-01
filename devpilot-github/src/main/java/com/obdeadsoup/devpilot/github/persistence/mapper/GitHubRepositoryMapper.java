package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Repository Binding 的复杂持久化边界。
 *
 * <p>所有用户态更新同时限定 Workspace/Project Scope、deleted、当前状态和 expectedVersion；
 * Metadata 200 与 304 分别使用独立 UPDATE，避免 304 覆盖权威字段或校验器。</p>
 */
@Mapper
public interface GitHubRepositoryMapper {

    String COLUMNS = """
            id,
            workspace_id AS workspaceId,
            project_id AS projectId,
            github_repository_id AS githubRepositoryId,
            owner_login AS ownerLogin,
            repository_name AS repositoryName,
            full_name AS fullName,
            html_url AS htmlUrl,
            default_branch AS defaultBranch,
            visibility,
            binding_status AS bindingStatus,
            webhook_secret_ref AS webhookSecretRef,
            api_credential_ref AS apiCredentialRef,
            last_synced_at AS lastSyncedAt,
            last_verified_at AS lastVerifiedAt,
            metadata_etag AS metadataEtag,
            metadata_last_modified AS metadataLastModified,
            created_by AS createdBy,
            created_at AS createdAt,
            updated_at AS updatedAt,
            version
            """;

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_repository
            WHERE github_repository_id = #{githubRepositoryId}
              AND deleted = 0
            """)
    Optional<GitHubRepositoryEntity> findByGitHubRepositoryId(
            @Param("githubRepositoryId") long githubRepositoryId
    );

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_repository
            WHERE github_repository_id = #{githubRepositoryId}
              AND deleted = 0
            FOR UPDATE
            """)
    Optional<GitHubRepositoryEntity> findActiveByGitHubRepositoryIdForUpdate(
            @Param("githubRepositoryId") long githubRepositoryId
    );

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_repository
            WHERE workspace_id = #{workspaceId}
              AND full_name = #{fullName}
              AND deleted = 0
            """)
    Optional<GitHubRepositoryEntity> findActiveByWorkspaceAndFullName(
            @Param("workspaceId") long workspaceId,
            @Param("fullName") String fullName
    );

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_repository
            WHERE workspace_id = #{workspaceId}
              AND full_name = #{fullName}
              AND deleted = 0
            FOR UPDATE
            """)
    Optional<GitHubRepositoryEntity> findActiveByWorkspaceAndFullNameForUpdate(
            @Param("workspaceId") long workspaceId,
            @Param("fullName") String fullName
    );

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_repository
            WHERE id = #{bindingId}
              AND workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND deleted = 0
            """)
    Optional<GitHubRepositoryEntity> findByScope(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("bindingId") long bindingId
    );

    @Insert("""
            INSERT INTO dp_github_repository (
                workspace_id, project_id, github_repository_id, owner_login,
                repository_name, full_name, html_url, default_branch, visibility,
                binding_status, webhook_secret_ref, api_credential_ref,
                last_verified_at, metadata_etag, metadata_last_modified, created_by, version
            ) VALUES (
                #{workspaceId}, #{projectId}, #{githubRepositoryId}, #{ownerLogin},
                #{repositoryName}, #{fullName}, #{htmlUrl}, #{defaultBranch}, #{visibility},
                'ACTIVE', #{webhookSecretRef}, #{apiCredentialRef},
                #{lastVerifiedAt}, #{metadataEtag}, #{metadataLastModified}, #{createdBy}, 0
            )
            """)
    int insert(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("githubRepositoryId") long githubRepositoryId,
            @Param("ownerLogin") String ownerLogin,
            @Param("repositoryName") String repositoryName,
            @Param("fullName") String fullName,
            @Param("htmlUrl") String htmlUrl,
            @Param("defaultBranch") String defaultBranch,
            @Param("visibility") String visibility,
            @Param("webhookSecretRef") String webhookSecretRef,
            @Param("apiCredentialRef") String apiCredentialRef,
            @Param("lastVerifiedAt") LocalDateTime lastVerifiedAt,
            @Param("metadataEtag") String metadataEtag,
            @Param("metadataLastModified") LocalDateTime metadataLastModified,
            @Param("createdBy") long createdBy
    );

    @Select("""
            SELECT COUNT(*)
            FROM dp_github_repository
            WHERE workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND deleted = 0
              AND (#{status} IS NULL OR binding_status = #{status})
            """)
    long countByProject(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("status") String status
    );

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_repository
            WHERE workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND deleted = 0
              AND (#{status} IS NULL OR binding_status = #{status})
            ORDER BY updated_at DESC, id DESC
            LIMIT #{offset}, #{size}
            """)
    List<GitHubRepositoryEntity> findByProject(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("status") String status,
            @Param("offset") long offset,
            @Param("size") int size
    );

    @Update("""
            UPDATE dp_github_repository
            SET binding_status = 'DISABLED',
                version = version + 1
            WHERE id = #{bindingId}
              AND workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND deleted = 0
              AND binding_status = 'ACTIVE'
              AND version = #{expectedVersion}
            """)
    int disable(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("bindingId") long bindingId,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_github_repository
            SET owner_login = #{ownerLogin},
                repository_name = #{repositoryName},
                full_name = #{fullName},
                html_url = #{htmlUrl},
                default_branch = #{defaultBranch},
                visibility = #{visibility},
                binding_status = 'ACTIVE',
                last_verified_at = #{lastVerifiedAt},
                metadata_etag = #{metadataEtag},
                metadata_last_modified = #{metadataLastModified},
                version = version + 1
            WHERE id = #{bindingId}
              AND workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND deleted = 0
              AND binding_status = 'DISABLED'
              AND version = #{expectedVersion}
            """)
    int reactivate(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("bindingId") long bindingId,
            @Param("expectedVersion") long expectedVersion,
            @Param("ownerLogin") String ownerLogin,
            @Param("repositoryName") String repositoryName,
            @Param("fullName") String fullName,
            @Param("htmlUrl") String htmlUrl,
            @Param("defaultBranch") String defaultBranch,
            @Param("visibility") String visibility,
            @Param("lastVerifiedAt") LocalDateTime lastVerifiedAt,
            @Param("metadataEtag") String metadataEtag,
            @Param("metadataLastModified") LocalDateTime metadataLastModified
    );

    @Update("""
            UPDATE dp_github_repository
            SET owner_login = #{ownerLogin},
                repository_name = #{repositoryName},
                full_name = #{fullName},
                html_url = #{htmlUrl},
                default_branch = #{defaultBranch},
                visibility = #{visibility},
                last_verified_at = #{lastVerifiedAt},
                metadata_etag = #{metadataEtag},
                metadata_last_modified = #{metadataLastModified},
                version = version + 1
            WHERE id = #{bindingId}
              AND workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND deleted = 0
              AND binding_status IN ('ACTIVE', 'DISABLED')
              AND version = #{expectedVersion}
            """)
    int refreshMetadata(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("bindingId") long bindingId,
            @Param("expectedVersion") long expectedVersion,
            @Param("ownerLogin") String ownerLogin,
            @Param("repositoryName") String repositoryName,
            @Param("fullName") String fullName,
            @Param("htmlUrl") String htmlUrl,
            @Param("defaultBranch") String defaultBranch,
            @Param("visibility") String visibility,
            @Param("lastVerifiedAt") LocalDateTime lastVerifiedAt,
            @Param("metadataEtag") String metadataEtag,
            @Param("metadataLastModified") LocalDateTime metadataLastModified
    );

    @Update("""
            UPDATE dp_github_repository
            SET last_verified_at = #{lastVerifiedAt},
                version = version + 1
            WHERE id = #{bindingId}
              AND workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND deleted = 0
              AND binding_status IN ('ACTIVE', 'DISABLED')
              AND version = #{expectedVersion}
            """)
    int markMetadataNotModified(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("bindingId") long bindingId,
            @Param("expectedVersion") long expectedVersion,
            @Param("lastVerifiedAt") LocalDateTime lastVerifiedAt
    );

    @Update("""
            UPDATE dp_github_repository
            SET deleted = 1,
                version = version + 1
            WHERE id = #{bindingId}
              AND workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND deleted = 0
              AND binding_status IN ('ACTIVE', 'DISABLED')
              AND version = #{expectedVersion}
            """)
    int unbind(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("bindingId") long bindingId,
            @Param("expectedVersion") long expectedVersion
    );
}
