package com.obdeadsoup.devpilot.project.persistence.mapper;

import com.obdeadsoup.devpilot.project.persistence.entity.ProjectEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ProjectMapper {
    /** Scope 同时限定 Workspace/Project；只返回 ACTIVE 本地成员，INVITED/SUSPENDED/REMOVED 不参与。 */
    @Select("""
            SELECT user_id FROM (
              SELECT w.owner_user_id AS user_id FROM dp_workspace w JOIN dp_project p ON p.workspace_id=w.id
               WHERE w.id=#{workspaceId} AND p.id=#{projectId} AND w.status='ACTIVE' AND w.deleted=0 AND p.deleted=0
              UNION
              SELECT wm.user_id FROM dp_workspace_member wm JOIN dp_project p ON p.workspace_id=wm.workspace_id
               WHERE wm.workspace_id=#{workspaceId} AND p.id=#{projectId} AND wm.status='ACTIVE' AND wm.role='ADMIN' AND p.deleted=0
              UNION
              SELECT pm.user_id FROM dp_project_member pm JOIN dp_project p ON p.id=pm.project_id AND p.workspace_id=pm.workspace_id
               WHERE pm.workspace_id=#{workspaceId} AND pm.project_id=#{projectId} AND pm.status='ACTIVE' AND pm.role='PROJECT_ADMIN' AND p.deleted=0
            ) managers WHERE user_id IS NOT NULL ORDER BY user_id
            """)
    List<Long> findNotificationManagers(@Param("workspaceId") long workspaceId,@Param("projectId") long projectId);

    @Select("""
            SELECT COUNT(*) FROM dp_project p JOIN dp_workspace w ON w.id=p.workspace_id
            WHERE p.id=#{projectId} AND p.workspace_id=#{workspaceId} AND p.deleted=0 AND p.status<>'ARCHIVED'
              AND w.deleted=0 AND w.status='ACTIVE' AND EXISTS (SELECT 1 FROM dp_user u WHERE u.id=#{userId} AND u.status='ACTIVE' AND u.deleted=0)
              AND (w.owner_user_id=#{userId} OR EXISTS (SELECT 1 FROM dp_workspace_member wm WHERE wm.workspace_id=w.id AND wm.user_id=#{userId} AND wm.status='ACTIVE'))
            """)
    int countActiveNotificationRecipient(@Param("userId") long userId,@Param("workspaceId") long workspaceId,@Param("projectId") long projectId);

    String COLUMNS = """
            id,
            workspace_id AS workspaceId,
            name,
            project_key AS projectKey,
            description,
            status,
            visibility,
            created_by AS createdBy,
            created_at AS createdAt,
            updated_at AS updatedAt,
            version,
            deleted
            """;

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_project
            WHERE id = #{projectId}
              AND workspace_id = #{workspaceId}
              AND deleted = 0
            """)
    Optional<ProjectEntity> findByScope(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId
    );

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_project
            WHERE workspace_id = #{workspaceId}
              AND project_key = #{projectKey}
              AND deleted = 0
            """)
    Optional<ProjectEntity> findByKey(
            @Param("workspaceId") long workspaceId,
            @Param("projectKey") String projectKey
    );

    @Insert("""
            INSERT INTO dp_project (
                workspace_id, name, project_key, description, status,
                visibility, created_by, version
            ) VALUES (
                #{workspaceId}, #{name}, #{projectKey}, #{description}, 'PLANNING',
                #{visibility}, #{createdBy}, 0
            )
            """)
    int insert(
            @Param("workspaceId") long workspaceId,
            @Param("name") String name,
            @Param("projectKey") String projectKey,
            @Param("description") String description,
            @Param("visibility") String visibility,
            @Param("createdBy") long createdBy
    );

    @Select("""
            SELECT COUNT(*)
            FROM dp_project p
            JOIN dp_workspace w ON w.id = p.workspace_id
            WHERE p.workspace_id = #{workspaceId}
              AND p.deleted = 0
              AND w.status = 'ACTIVE'
              AND w.deleted = 0
              AND (#{status} IS NULL OR p.status = #{status})
              AND (#{visibility} IS NULL OR p.visibility = #{visibility})
              AND (
                w.owner_user_id = #{userId}
                OR EXISTS (
                    SELECT 1 FROM dp_workspace_member wm
                    WHERE wm.workspace_id = w.id
                      AND wm.user_id = #{userId}
                      AND wm.status = 'ACTIVE'
                )
              )
              AND (
                w.owner_user_id = #{userId}
                OR EXISTS (
                    SELECT 1 FROM dp_workspace_member wa
                    WHERE wa.workspace_id = w.id
                      AND wa.user_id = #{userId}
                      AND wa.role = 'ADMIN'
                      AND wa.status = 'ACTIVE'
                )
                OR p.visibility = 'INTERNAL'
                OR EXISTS (
                    SELECT 1 FROM dp_project_member pm
                    WHERE pm.workspace_id = p.workspace_id
                      AND pm.project_id = p.id
                      AND pm.user_id = #{userId}
                      AND pm.status = 'ACTIVE'
                )
              )
            """)
    long countVisible(
            @Param("userId") long userId,
            @Param("workspaceId") long workspaceId,
            @Param("status") String status,
            @Param("visibility") String visibility
    );

    @Select("""
            SELECT p.id,
                   p.workspace_id AS workspaceId,
                   p.name,
                   p.project_key AS projectKey,
                   p.description,
                   p.status,
                   p.visibility,
                   p.created_by AS createdBy,
                   p.created_at AS createdAt,
                   p.updated_at AS updatedAt,
                   p.version,
                   p.deleted
            FROM dp_project p
            JOIN dp_workspace w ON w.id = p.workspace_id
            WHERE p.workspace_id = #{workspaceId}
              AND p.deleted = 0
              AND w.status = 'ACTIVE'
              AND w.deleted = 0
              AND (#{status} IS NULL OR p.status = #{status})
              AND (#{visibility} IS NULL OR p.visibility = #{visibility})
              AND (
                w.owner_user_id = #{userId}
                OR EXISTS (
                    SELECT 1 FROM dp_workspace_member wm
                    WHERE wm.workspace_id = w.id
                      AND wm.user_id = #{userId}
                      AND wm.status = 'ACTIVE'
                )
              )
              AND (
                w.owner_user_id = #{userId}
                OR EXISTS (
                    SELECT 1 FROM dp_workspace_member wa
                    WHERE wa.workspace_id = w.id
                      AND wa.user_id = #{userId}
                      AND wa.role = 'ADMIN'
                      AND wa.status = 'ACTIVE'
                )
                OR p.visibility = 'INTERNAL'
                OR EXISTS (
                    SELECT 1 FROM dp_project_member pm
                    WHERE pm.workspace_id = p.workspace_id
                      AND pm.project_id = p.id
                      AND pm.user_id = #{userId}
                      AND pm.status = 'ACTIVE'
                )
              )
            ORDER BY p.updated_at DESC, p.id DESC
            LIMIT #{offset}, #{size}
            """)
    List<ProjectEntity> findVisible(
            @Param("userId") long userId,
            @Param("workspaceId") long workspaceId,
            @Param("status") String status,
            @Param("visibility") String visibility,
            @Param("offset") long offset,
            @Param("size") int size
    );

    @Update("""
            UPDATE dp_project
            SET name = #{name},
                description = #{description},
                visibility = #{visibility},
                version = version + 1
            WHERE id = #{projectId}
              AND workspace_id = #{workspaceId}
              AND version = #{expectedVersion}
              AND status <> 'ARCHIVED'
              AND deleted = 0
            """)
    int updateProfile(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("name") String name,
            @Param("description") String description,
            @Param("visibility") String visibility,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_project
            SET status = 'ACTIVE', version = version + 1
            WHERE id = #{projectId}
              AND workspace_id = #{workspaceId}
              AND status = 'PLANNING'
              AND version = #{expectedVersion}
              AND deleted = 0
            """)
    int activate(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_project
            SET status = 'ARCHIVED', version = version + 1
            WHERE id = #{projectId}
              AND workspace_id = #{workspaceId}
              AND status IN ('PLANNING', 'ACTIVE')
              AND version = #{expectedVersion}
              AND deleted = 0
            """)
    int archive(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_project
            SET status = 'ACTIVE', version = version + 1
            WHERE id = #{projectId}
              AND workspace_id = #{workspaceId}
              AND status = 'ARCHIVED'
              AND version = #{expectedVersion}
              AND deleted = 0
            """)
    int restore(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("expectedVersion") long expectedVersion
    );

    @Select("""
            SELECT COUNT(*)
            FROM dp_project p
            JOIN dp_workspace w ON w.id = p.workspace_id
            WHERE p.id = #{projectId}
              AND p.workspace_id = #{workspaceId}
              AND p.status <> 'ARCHIVED'
              AND p.deleted = 0
              AND w.status = 'ACTIVE'
              AND w.deleted = 0
            """)
    int countActiveProjectScope(@Param("workspaceId") long workspaceId, @Param("projectId") long projectId);

    @Select("""
            SELECT COUNT(*)
            FROM dp_project p
            JOIN dp_workspace w ON w.id = p.workspace_id
            WHERE p.id = #{projectId}
              AND p.workspace_id = #{workspaceId}
              AND p.deleted = 0
              AND w.status = 'ACTIVE'
              AND w.deleted = 0
            """)
    int countProjectScopeInActiveWorkspace(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId
    );
}
