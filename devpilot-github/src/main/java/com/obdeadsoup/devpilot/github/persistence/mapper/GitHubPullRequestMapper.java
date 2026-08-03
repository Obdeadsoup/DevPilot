package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestCommand;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** PR 当前快照与有界 Review 候选 Mapper；不会在每轮扫描全部历史 PR。 */
@Mapper
public interface GitHubPullRequestMapper {

    String COLUMNS = """
            id, workspace_id AS workspaceId, project_id AS projectId,
            repository_binding_id AS repositoryBindingId,
            github_repository_id AS githubRepositoryId,
            github_pull_request_id AS githubPullRequestId, github_issue_id AS githubIssueId,
            pull_request_number AS pullRequestNumber, title, body, status, draft,
            author_github_user_id AS authorGitHubUserId, author_login AS authorLogin,
            head_ref AS headRef, head_sha AS headSha, base_ref AS baseRef, base_sha AS baseSha,
            merge_commit_sha AS mergeCommitSha, requested_reviewers_json AS requestedReviewersJson,
            assignee_summary_json AS assigneeSummaryJson, labels_json AS labelsJson,
            html_url AS htmlUrl, github_created_at AS githubCreatedAt,
            github_updated_at AS githubUpdatedAt, github_closed_at AS githubClosedAt,
            github_merged_at AS githubMergedAt, reviews_synced_at AS reviewsSyncedAt,
            first_seen_source AS firstSeenSource, content_hash AS contentHash,
            created_at AS createdAt, updated_at AS updatedAt, version
            """;

    @Insert("""
            INSERT INTO dp_github_pull_request (
                workspace_id, project_id, repository_binding_id, github_repository_id,
                github_pull_request_id, github_issue_id, pull_request_number, title, body,
                status, draft, author_github_user_id, author_login, head_ref, head_sha,
                base_ref, base_sha, merge_commit_sha, requested_reviewers_json,
                assignee_summary_json, labels_json, html_url, github_created_at,
                github_updated_at, github_closed_at, github_merged_at, first_seen_source, content_hash
            ) VALUES (
                #{c.workspaceId}, #{c.projectId}, #{c.repositoryBindingId}, #{c.githubRepositoryId},
                #{c.githubPullRequestId}, #{c.githubIssueId}, #{c.pullRequestNumber}, #{c.title}, #{c.body},
                #{c.status}, #{c.draft}, #{c.authorGitHubUserId}, #{c.authorLogin}, #{c.headRef}, #{c.headSha},
                #{c.baseRef}, #{c.baseSha}, #{c.mergeCommitSha}, #{c.requestedReviewersJson},
                #{c.assigneeSummaryJson}, #{c.labelsJson}, #{c.htmlUrl}, #{c.githubCreatedAt},
                #{c.githubUpdatedAt}, #{c.githubClosedAt}, #{c.githubMergedAt}, #{c.source}, #{c.contentHash}
            )
            """)
    int insert(@Param("c") UpsertGitHubPullRequestCommand command);

    @Select("SELECT " + COLUMNS + " FROM dp_github_pull_request WHERE github_repository_id=#{repositoryId} AND github_pull_request_id=#{githubPullRequestId}")
    Optional<GitHubPullRequestEntity> findByRepositoryAndGitHubId(
            @Param("repositoryId") long repositoryId,
            @Param("githubPullRequestId") long githubPullRequestId);

    @Select("SELECT " + COLUMNS + " FROM dp_github_pull_request WHERE github_repository_id=#{repositoryId} AND pull_request_number=#{number}")
    Optional<GitHubPullRequestEntity> findByRepositoryAndNumber(
            @Param("repositoryId") long repositoryId, @Param("number") int number);

    @Update("""
            UPDATE dp_github_pull_request
            SET github_issue_id=#{c.githubIssueId}, pull_request_number=#{c.pullRequestNumber},
                title=#{c.title}, body=#{c.body}, status=#{c.status}, draft=#{c.draft},
                author_github_user_id=#{c.authorGitHubUserId}, author_login=#{c.authorLogin},
                head_ref=#{c.headRef}, head_sha=#{c.headSha}, base_ref=#{c.baseRef}, base_sha=#{c.baseSha},
                merge_commit_sha=#{c.mergeCommitSha}, requested_reviewers_json=#{c.requestedReviewersJson},
                assignee_summary_json=#{c.assigneeSummaryJson}, labels_json=#{c.labelsJson},
                html_url=#{c.htmlUrl}, github_created_at=#{c.githubCreatedAt},
                github_updated_at=#{c.githubUpdatedAt}, github_closed_at=#{c.githubClosedAt},
                github_merged_at=#{c.githubMergedAt}, content_hash=#{c.contentHash}, version=version+1
            WHERE id=#{id} AND version=#{version} AND github_updated_at <= #{c.githubUpdatedAt}
            """)
    int updateSnapshot(@Param("id") long id, @Param("version") long version,
                       @Param("c") UpsertGitHubPullRequestCommand command);

    @Select("SELECT COUNT(*) FROM dp_github_pull_request WHERE workspace_id=#{workspaceId} AND project_id=#{projectId}")
    long countByProject(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId);

    @Select("SELECT " + COLUMNS + " FROM dp_github_pull_request WHERE workspace_id=#{workspaceId} AND project_id=#{projectId} ORDER BY github_updated_at DESC,id DESC LIMIT #{size} OFFSET #{offset}")
    List<GitHubPullRequestEntity> findPageByProject(@Param("workspaceId") long workspaceId,
                                                    @Param("projectId") long projectId,
                                                    @Param("offset") long offset, @Param("size") int size);

    @Select("SELECT " + COLUMNS + " FROM dp_github_pull_request WHERE id=#{id} AND workspace_id=#{workspaceId} AND project_id=#{projectId}")
    Optional<GitHubPullRequestEntity> findByProjectAndId(@Param("workspaceId") long workspaceId,
                                                         @Param("projectId") long projectId,
                                                         @Param("id") long id);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_github_pull_request
            WHERE repository_binding_id=#{bindingId} AND github_updated_at >= #{activeSince}
              AND (reviews_synced_at IS NULL OR reviews_synced_at < github_updated_at)
            ORDER BY github_updated_at DESC, id DESC LIMIT #{limit}
            """)
    List<GitHubPullRequestEntity> findReviewCandidates(@Param("bindingId") long bindingId,
                                                       @Param("activeSince") LocalDateTime activeSince,
                                                       @Param("limit") int limit);

    @Update("UPDATE dp_github_pull_request SET reviews_synced_at=#{syncedAt}, version=version+1 WHERE id=#{id} AND version=#{version}")
    int markReviewsSynced(@Param("id") long id, @Param("version") long version,
                          @Param("syncedAt") LocalDateTime syncedAt);
}
