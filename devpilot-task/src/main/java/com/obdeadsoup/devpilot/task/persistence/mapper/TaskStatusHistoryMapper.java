package com.obdeadsoup.devpilot.task.persistence.mapper;

import com.obdeadsoup.devpilot.task.persistence.entity.TaskStatusHistoryEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;

/** 不可变状态历史 Mapper；只暴露 insert 与 scoped read，普通 API 没有修改/删除入口。 */
@Mapper
public interface TaskStatusHistoryMapper {
    @Insert("""
            INSERT INTO dp_task_status_history (workspace_id, project_id, task_id, from_status, to_status, action,
                actor_user_id, reason, task_version, occurred_at)
            VALUES (#{workspaceId},#{projectId},#{taskId},#{fromStatus},#{toStatus},#{action},#{actorUserId},#{reason},#{taskVersion},#{occurredAt})
            """)
    int insert(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId, @Param("taskId") long taskId,
               @Param("fromStatus") String fromStatus, @Param("toStatus") String toStatus, @Param("action") String action,
               @Param("actorUserId") long actorUserId, @Param("reason") String reason, @Param("taskVersion") long taskVersion,
               @Param("occurredAt") LocalDateTime occurredAt);

    @Select("""
            SELECT id,workspace_id AS workspaceId,project_id AS projectId,task_id AS taskId,from_status AS fromStatus,
                   to_status AS toStatus,action,actor_user_id AS actorUserId,reason,task_version AS taskVersion,occurred_at AS occurredAt
            FROM dp_task_status_history WHERE workspace_id=#{workspaceId} AND project_id=#{projectId} AND task_id=#{taskId}
            ORDER BY task_version ASC
            """)
    List<TaskStatusHistoryEntity> findByTaskScope(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                                                   @Param("taskId") long taskId);
}
