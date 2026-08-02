package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubCommitCommand;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubCommitEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

/**
 * Commit 明细的持久化边界。数据库唯一键负责 Repository + SHA 的最终并发幂等，
 * version 条件更新只保护同一行的安全元数据不被并发旧版本覆盖。
 */
@Mapper
public interface GitHubCommitMapper {

    String COLUMNS = """
            id, workspace_id AS workspaceId, project_id AS projectId,
            repository_binding_id AS repositoryBindingId,
            github_repository_id AS githubRepositoryId, commit_sha AS commitSha,
            message, author_name AS authorName, author_email AS authorEmail,
            author_github_user_id AS authorGitHubUserId, author_login AS authorLogin,
            committed_at AS committedAt, html_url AS htmlUrl,
            first_seen_source AS firstSeenSource, created_at AS createdAt,
            updated_at AS updatedAt, version
            """;

    @Insert("""
            INSERT INTO dp_github_commit (
                workspace_id, project_id, repository_binding_id, github_repository_id,
                commit_sha, message, author_name, author_email, author_github_user_id,
                author_login, committed_at, html_url, first_seen_source
            ) VALUES (
                #{command.workspaceId}, #{command.projectId}, #{command.repositoryBindingId},
                #{command.githubRepositoryId}, #{command.commitSha}, #{command.message},
                #{command.authorName}, #{command.authorEmail}, #{command.authorGitHubUserId},
                #{command.authorLogin}, #{command.committedAt}, #{command.htmlUrl}, #{command.source}
            )
            """)
    int insert(@Param("command") UpsertGitHubCommitCommand command);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_commit
            WHERE github_repository_id = #{githubRepositoryId}
              AND commit_sha = #{commitSha}
            """)
    Optional<GitHubCommitEntity> findByRepositoryAndSha(
            @Param("githubRepositoryId") long githubRepositoryId,
            @Param("commitSha") String commitSha
    );

    @Update("""
            UPDATE dp_github_commit
            SET message = COALESCE(#{command.message}, message),
                author_name = COALESCE(#{command.authorName}, author_name),
                author_email = COALESCE(#{command.authorEmail}, author_email),
                author_github_user_id = COALESCE(#{command.authorGitHubUserId}, author_github_user_id),
                author_login = COALESCE(#{command.authorLogin}, author_login),
                committed_at = #{command.committedAt},
                html_url = COALESCE(#{command.htmlUrl}, html_url),
                version = version + 1
            WHERE id = #{id}
              AND version = #{expectedVersion}
            """)
    int updateSafeMetadata(
            @Param("id") long id,
            @Param("expectedVersion") long expectedVersion,
            @Param("command") UpsertGitHubCommitCommand command
    );
}
