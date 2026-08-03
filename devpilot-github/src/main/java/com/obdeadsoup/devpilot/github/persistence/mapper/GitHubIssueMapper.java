package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubIssueCommand;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubIssueEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/**
 * Issue 当前快照 Mapper。Repository + GitHub stable ID/number 唯一键负责并发身份仲裁；
 * 更新同时携带外部 github_updated_at 与本地 version，分别阻止乱序覆盖和本地并发覆盖。
 */
@Mapper
public interface GitHubIssueMapper {

    String COLUMNS = """
            id, workspace_id AS workspaceId, project_id AS projectId,
            repository_binding_id AS repositoryBindingId,
            github_repository_id AS githubRepositoryId, github_issue_id AS githubIssueId,
            issue_number AS issueNumber, title, body, state, state_reason AS stateReason,
            author_github_user_id AS authorGitHubUserId, author_login AS authorLogin,
            assignee_summary_json AS assigneeSummaryJson, labels_json AS labelsJson,
            html_url AS htmlUrl, github_created_at AS githubCreatedAt,
            github_updated_at AS githubUpdatedAt, github_closed_at AS githubClosedAt,
            first_seen_source AS firstSeenSource, content_hash AS contentHash,
            created_at AS createdAt, updated_at AS updatedAt, version
            """;

    @Insert("""
            INSERT INTO dp_github_issue (
                workspace_id, project_id, repository_binding_id, github_repository_id,
                github_issue_id, issue_number, title, body, state, state_reason,
                author_github_user_id, author_login, assignee_summary_json, labels_json,
                html_url, github_created_at, github_updated_at, github_closed_at,
                first_seen_source, content_hash
            ) VALUES (
                #{c.workspaceId}, #{c.projectId}, #{c.repositoryBindingId}, #{c.githubRepositoryId},
                #{c.githubIssueId}, #{c.issueNumber}, #{c.title}, #{c.body}, #{c.status}, #{c.stateReason},
                #{c.authorGitHubUserId}, #{c.authorLogin}, #{c.assigneeSummaryJson}, #{c.labelsJson},
                #{c.htmlUrl}, #{c.githubCreatedAt}, #{c.githubUpdatedAt}, #{c.githubClosedAt},
                #{c.source}, #{c.contentHash}
            )
            """)
    int insert(@Param("c") UpsertGitHubIssueCommand command);

    @Select("SELECT " + COLUMNS + " FROM dp_github_issue WHERE github_repository_id=#{repositoryId} AND github_issue_id=#{githubIssueId}")
    Optional<GitHubIssueEntity> findByRepositoryAndGitHubId(
            @Param("repositoryId") long repositoryId, @Param("githubIssueId") long githubIssueId);

    @Update("""
            UPDATE dp_github_issue
            SET issue_number=#{c.issueNumber}, title=#{c.title}, body=#{c.body}, state=#{c.status},
                state_reason=#{c.stateReason}, author_github_user_id=#{c.authorGitHubUserId},
                author_login=#{c.authorLogin}, assignee_summary_json=#{c.assigneeSummaryJson},
                labels_json=#{c.labelsJson}, html_url=#{c.htmlUrl},
                github_created_at=#{c.githubCreatedAt}, github_updated_at=#{c.githubUpdatedAt},
                github_closed_at=#{c.githubClosedAt}, content_hash=#{c.contentHash}, version=version+1
            WHERE id=#{id} AND version=#{version} AND github_updated_at <= #{c.githubUpdatedAt}
            """)
    int updateSnapshot(@Param("id") long id, @Param("version") long version,
                       @Param("c") UpsertGitHubIssueCommand command);

    @Select("SELECT COUNT(*) FROM dp_github_issue WHERE workspace_id=#{workspaceId} AND project_id=#{projectId}")
    long countByProject(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_issue WHERE workspace_id=#{workspaceId} AND project_id=#{projectId}
            ORDER BY github_updated_at DESC, id DESC LIMIT #{size} OFFSET #{offset}
            """)
    List<GitHubIssueEntity> findPageByProject(@Param("workspaceId") long workspaceId,
                                               @Param("projectId") long projectId,
                                               @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT " + COLUMNS + " FROM dp_github_issue WHERE id=#{id} AND workspace_id=#{workspaceId} AND project_id=#{projectId}")
    Optional<GitHubIssueEntity> findByProjectAndId(@Param("workspaceId") long workspaceId,
                                                   @Param("projectId") long projectId,
                                                   @Param("id") long id);
}
