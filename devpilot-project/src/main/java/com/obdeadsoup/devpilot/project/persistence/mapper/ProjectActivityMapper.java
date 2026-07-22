package com.obdeadsoup.devpilot.project.persistence.mapper;

import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;
import com.obdeadsoup.devpilot.project.persistence.entity.ProjectActivityEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

@Mapper
public interface ProjectActivityMapper {

    @Insert("""
            INSERT INTO dp_project_activity (
                workspace_id, project_id, github_repository_id, repository_full_name,
                source_type, activity_type, source_delivery_id, external_actor_id, actor_login,
                git_ref, before_sha, after_sha, commit_count, head_commit_message,
                title, summary, external_url, occurred_at
            ) VALUES (
                #{command.workspaceId}, #{command.projectId}, #{command.githubRepositoryId},
                #{command.repositoryFullName}, #{command.sourceType}, #{command.activityType},
                #{command.sourceDeliveryId}, #{command.externalActorId}, #{command.actorLogin},
                #{command.gitRef}, #{command.beforeSha}, #{command.afterSha}, #{command.commitCount},
                #{command.headCommitMessage}, #{command.title}, #{command.summary},
                #{command.externalUrl}, #{command.occurredAt}
            )
            ON DUPLICATE KEY UPDATE id = id
            """)
    int insertIfAbsent(@Param("command") RecordProjectActivityCommand command);

    @Select("""
            SELECT COUNT(*)
            FROM dp_project_activity
            WHERE workspace_id = #{workspaceId} AND project_id = #{projectId}
            """)
    long countTimeline(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId);

    @Select("""
            SELECT id,
                   workspace_id AS workspaceId,
                   project_id AS projectId,
                   github_repository_id AS githubRepositoryId,
                   repository_full_name AS repositoryFullName,
                   source_type AS sourceType,
                   activity_type AS activityType,
                   source_delivery_id AS sourceDeliveryId,
                   external_actor_id AS externalActorId,
                   actor_login AS actorLogin,
                   git_ref AS gitRef,
                   before_sha AS beforeSha,
                   after_sha AS afterSha,
                   commit_count AS commitCount,
                   head_commit_message AS headCommitMessage,
                   title,
                   summary,
                   external_url AS externalUrl,
                   occurred_at AS occurredAt
            FROM dp_project_activity
            WHERE workspace_id = #{workspaceId} AND project_id = #{projectId}
            ORDER BY occurred_at DESC, id DESC
            LIMIT #{size} OFFSET #{offset}
            """)
    List<ProjectActivityEntity> findTimeline(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("offset") long offset,
            @Param("size") int size
    );
}
