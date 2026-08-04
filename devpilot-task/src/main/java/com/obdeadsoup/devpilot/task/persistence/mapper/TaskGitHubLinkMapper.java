package com.obdeadsoup.devpilot.task.persistence.mapper;

import com.obdeadsoup.devpilot.task.persistence.entity.TaskGitHubLinkEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

/** 关联 Mapper 只接受 Adapter 提供的稳定 ID；ACTIVE 生成列唯一键是跨请求的最终仲裁。 */
@Mapper
public interface TaskGitHubLinkMapper {
    String COLUMNS = """
            id,workspace_id AS workspaceId,project_id AS projectId,task_id AS taskId,
            repository_binding_id AS repositoryBindingId,github_repository_id AS githubRepositoryId,
            resource_type AS resourceType,relation_type AS relationType,issue_snapshot_id AS issueSnapshotId,
            pull_request_snapshot_id AS pullRequestSnapshotId,github_object_id AS githubObjectId,
            github_number AS githubNumber,link_status AS linkStatus,created_by AS createdBy,removed_by AS removedBy,
            created_at AS createdAt,removed_at AS removedAt,version
            """;
    @Insert("""
            INSERT INTO dp_task_github_link (workspace_id,project_id,task_id,repository_binding_id,github_repository_id,
                resource_type,relation_type,issue_snapshot_id,pull_request_snapshot_id,github_object_id,github_number,created_by)
            VALUES (#{workspaceId},#{projectId},#{taskId},#{bindingId},#{repositoryId},#{resourceType},#{relationType},
                #{issueSnapshotId},#{pullRequestSnapshotId},#{githubObjectId},#{githubNumber},#{actorUserId})
            """)
    int insert(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId, @Param("taskId") long taskId,
               @Param("bindingId") long bindingId, @Param("repositoryId") long repositoryId,
               @Param("resourceType") String resourceType, @Param("relationType") String relationType,
               @Param("issueSnapshotId") Long issueSnapshotId, @Param("pullRequestSnapshotId") Long pullRequestSnapshotId,
               @Param("githubObjectId") long githubObjectId, @Param("githubNumber") int githubNumber,
               @Param("actorUserId") long actorUserId);
    @Select("SELECT " + COLUMNS + " FROM dp_task_github_link WHERE active_external_identity=#{identity}")
    Optional<TaskGitHubLinkEntity> findActiveByExternalIdentity(@Param("identity") String identity);
    @Select("SELECT " + COLUMNS + " FROM dp_task_github_link WHERE workspace_id=#{workspaceId} AND project_id=#{projectId} AND task_id=#{taskId} ORDER BY id")
    List<TaskGitHubLinkEntity> findByTaskScope(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                                                @Param("taskId") long taskId);
    @Select("SELECT " + COLUMNS + " FROM dp_task_github_link WHERE id=#{linkId} AND workspace_id=#{workspaceId} AND project_id=#{projectId} AND task_id=#{taskId}")
    Optional<TaskGitHubLinkEntity> findByTaskScopeAndId(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                                                         @Param("taskId") long taskId, @Param("linkId") long linkId);
    @Update("""
            UPDATE dp_task_github_link SET link_status='REMOVED',removed_by=#{actorUserId},removed_at=CURRENT_TIMESTAMP(6),version=version+1
            WHERE id=#{linkId} AND workspace_id=#{workspaceId} AND project_id=#{projectId} AND task_id=#{taskId}
              AND link_status='ACTIVE' AND version=#{expectedVersion}
            """)
    int remove(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId, @Param("taskId") long taskId,
               @Param("linkId") long linkId, @Param("actorUserId") long actorUserId, @Param("expectedVersion") long expectedVersion);
}
