package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubPullRequestReviewCommand;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestReviewEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/** Review 以独立 github_review_id 建立外部身份，避免把 reviewer、状态或 PR number 当作身份。 */
@Mapper
public interface GitHubPullRequestReviewMapper {

    String COLUMNS = """
            id, workspace_id AS workspaceId, project_id AS projectId,
            repository_binding_id AS repositoryBindingId,
            github_repository_id AS githubRepositoryId, pull_request_id AS pullRequestId,
            github_review_id AS githubReviewId, reviewer_github_user_id AS reviewerGitHubUserId,
            reviewer_login AS reviewerLogin, state, body, commit_sha AS commitSha,
            html_url AS htmlUrl, submitted_at AS submittedAt,
            github_updated_at AS githubUpdatedAt, first_seen_source AS firstSeenSource,
            content_hash AS contentHash, created_at AS createdAt, updated_at AS updatedAt, version
            """;

    @Insert("""
            INSERT INTO dp_github_pull_request_review (
                workspace_id, project_id, repository_binding_id, github_repository_id,
                pull_request_id, github_review_id, reviewer_github_user_id, reviewer_login,
                state, body, commit_sha, html_url, submitted_at, github_updated_at,
                first_seen_source, content_hash
            ) VALUES (
                #{c.workspaceId}, #{c.projectId}, #{c.repositoryBindingId}, #{c.githubRepositoryId},
                #{pullRequestId}, #{c.githubReviewId}, #{c.reviewerGitHubUserId}, #{c.reviewerLogin},
                #{c.status}, #{c.body}, #{c.commitSha}, #{c.htmlUrl}, #{c.submittedAt},
                #{c.githubUpdatedAt}, #{c.source}, #{c.contentHash}
            )
            """)
    int insert(@Param("pullRequestId") long pullRequestId,
               @Param("c") UpsertGitHubPullRequestReviewCommand command);

    @Select("SELECT " + COLUMNS + " FROM dp_github_pull_request_review WHERE github_repository_id=#{repositoryId} AND github_review_id=#{githubReviewId}")
    Optional<GitHubPullRequestReviewEntity> findByRepositoryAndGitHubId(
            @Param("repositoryId") long repositoryId, @Param("githubReviewId") long githubReviewId);

    @Update("""
            UPDATE dp_github_pull_request_review
            SET reviewer_github_user_id=#{c.reviewerGitHubUserId}, reviewer_login=#{c.reviewerLogin},
                state=#{c.status}, body=#{c.body}, commit_sha=#{c.commitSha}, html_url=#{c.htmlUrl},
                submitted_at=#{c.submittedAt}, github_updated_at=#{c.githubUpdatedAt},
                content_hash=#{c.contentHash}, version=version+1
            WHERE id=#{id} AND version=#{version} AND github_updated_at <= #{c.githubUpdatedAt}
            """)
    int updateSnapshot(@Param("id") long id, @Param("version") long version,
                       @Param("c") UpsertGitHubPullRequestReviewCommand command);

    @Select("SELECT " + COLUMNS + " FROM dp_github_pull_request_review WHERE pull_request_id=#{pullRequestId} AND workspace_id=#{workspaceId} AND project_id=#{projectId} ORDER BY submitted_at DESC,id DESC")
    List<GitHubPullRequestReviewEntity> findByPullRequestScope(
            @Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
            @Param("pullRequestId") long pullRequestId);
}
