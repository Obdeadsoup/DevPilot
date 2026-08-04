package com.obdeadsoup.devpilot.task.persistence.mapper;

import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Options;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

/** Task 的 scoped SQL。所有修改同时携带 scope、状态和 version，防止旧请求覆盖新状态。 */
@Mapper
public interface TaskMapper {
    String COLUMNS = """
            id, workspace_id AS workspaceId, project_id AS projectId, title, description, status, priority,
            reporter_user_id AS reporterUserId, assignee_user_id AS assigneeUserId, due_at AS dueAt,
            completed_at AS completedAt, canceled_at AS canceledAt, created_at AS createdAt,
            updated_at AS updatedAt, version, deleted
            """;

    @Insert("""
            INSERT INTO dp_task (workspace_id, project_id, title, description, status, priority,
                reporter_user_id, assignee_user_id, due_at, completed_at, canceled_at, version, deleted)
            VALUES (#{task.workspaceId}, #{task.projectId}, #{task.title}, #{task.description}, #{task.status},
                #{task.priority}, #{task.reporterUserId}, #{task.assigneeUserId}, #{task.dueAt},
                #{task.completedAt}, #{task.canceledAt}, #{task.version}, #{task.deleted})
            """)
    @Options(useGeneratedKeys = true, keyProperty = "task.id")
    int insert(@Param("task") TaskEntity task);

    @Select("SELECT " + COLUMNS + " FROM dp_task WHERE id=#{taskId} AND workspace_id=#{workspaceId} AND project_id=#{projectId} AND deleted=0")
    Optional<TaskEntity> findByScope(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                                     @Param("taskId") long taskId);

    @Select("""
            <script>SELECT COUNT(*) FROM dp_task WHERE workspace_id=#{workspaceId} AND project_id=#{projectId} AND deleted=0
            <if test='status != null'> AND status=#{status}</if><if test='priority != null'> AND priority=#{priority}</if>
            <if test='assigneeUserId != null'> AND assignee_user_id=#{assigneeUserId}</if>
            <if test='reporterUserId != null'> AND reporter_user_id=#{reporterUserId}</if>
            <if test='dueBefore != null'> AND due_at &lt;= #{dueBefore}</if></script>
            """)
    long countPage(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                   @Param("status") String status, @Param("priority") String priority,
                   @Param("assigneeUserId") Long assigneeUserId, @Param("reporterUserId") Long reporterUserId,
                   @Param("dueBefore") LocalDateTime dueBefore);

    @Select("<script>SELECT " + COLUMNS + """
             FROM dp_task WHERE workspace_id=#{workspaceId} AND project_id=#{projectId} AND deleted=0
            <if test='status != null'> AND status=#{status}</if><if test='priority != null'> AND priority=#{priority}</if>
            <if test='assigneeUserId != null'> AND assignee_user_id=#{assigneeUserId}</if>
            <if test='reporterUserId != null'> AND reporter_user_id=#{reporterUserId}</if>
            <if test='dueBefore != null'> AND due_at &lt;= #{dueBefore}</if>
            ORDER BY updated_at DESC, id DESC LIMIT #{size} OFFSET #{offset}</script>
            """)
    List<TaskEntity> findPage(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                              @Param("status") String status, @Param("priority") String priority,
                              @Param("assigneeUserId") Long assigneeUserId, @Param("reporterUserId") Long reporterUserId,
                              @Param("dueBefore") LocalDateTime dueBefore, @Param("offset") long offset,
                              @Param("size") int size);

    @Update("""
            UPDATE dp_task SET title=#{title}, description=#{description}, priority=#{priority}, due_at=#{dueAt}, version=version+1
            WHERE id=#{taskId} AND workspace_id=#{workspaceId} AND project_id=#{projectId} AND version=#{expectedVersion}
              AND deleted=0 AND status NOT IN ('DONE','CANCELED')
            """)
    int updateProfile(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                      @Param("taskId") long taskId, @Param("title") String title, @Param("description") String description,
                      @Param("priority") String priority, @Param("dueAt") LocalDateTime dueAt,
                      @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE dp_task SET assignee_user_id=#{assigneeUserId}, version=version+1
            WHERE id=#{taskId} AND workspace_id=#{workspaceId} AND project_id=#{projectId} AND version=#{expectedVersion}
              AND deleted=0 AND status NOT IN ('DONE','CANCELED')
            """)
    int updateAssignee(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                       @Param("taskId") long taskId, @Param("assigneeUserId") Long assigneeUserId,
                       @Param("expectedVersion") long expectedVersion);

    @Update("""
            UPDATE dp_task SET status=#{targetStatus}, completed_at=#{completedAt}, canceled_at=#{canceledAt}, version=version+1
            WHERE id=#{taskId} AND workspace_id=#{workspaceId} AND project_id=#{projectId} AND status=#{expectedStatus}
              AND version=#{expectedVersion} AND deleted=0
            """)
    int transition(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                   @Param("taskId") long taskId, @Param("expectedStatus") String expectedStatus,
                   @Param("targetStatus") String targetStatus, @Param("completedAt") LocalDateTime completedAt,
                   @Param("canceledAt") LocalDateTime canceledAt, @Param("expectedVersion") long expectedVersion);

    @Update("""
            <script>UPDATE dp_task SET version=version+1 WHERE id=#{taskId} AND workspace_id=#{workspaceId}
            AND project_id=#{projectId} AND version=#{expectedVersion} AND deleted=0
            <if test='!allowTerminal'> AND status NOT IN ('DONE','CANCELED')</if></script>
            """)
    int incrementVersionForLink(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId,
                                @Param("taskId") long taskId, @Param("expectedVersion") long expectedVersion,
                                @Param("allowTerminal") boolean allowTerminal);
}
