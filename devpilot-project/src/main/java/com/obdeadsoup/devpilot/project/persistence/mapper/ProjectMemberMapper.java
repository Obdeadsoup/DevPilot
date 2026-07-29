package com.obdeadsoup.devpilot.project.persistence.mapper;

import com.obdeadsoup.devpilot.project.persistence.entity.ProjectMemberEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface ProjectMemberMapper {

    @Select("""
            SELECT id,
                   workspace_id AS workspaceId,
                   project_id AS projectId,
                   user_id AS userId,
                   role,
                   status,
                   created_by AS createdBy,
                   version
            FROM dp_project_member
            WHERE workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND user_id = #{userId}
            """)
    Optional<ProjectMemberEntity> findByScopeAndUser(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("userId") long userId
    );

    @Select("""
            SELECT id,
                   workspace_id AS workspaceId,
                   project_id AS projectId,
                   user_id AS userId,
                   role,
                   status,
                   created_by AS createdBy,
                   version
            FROM dp_project_member
            WHERE workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND status = 'ACTIVE'
            ORDER BY id
            """)
    List<ProjectMemberEntity> findActiveByProjectScope(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId
    );

    @Insert("""
            INSERT INTO dp_project_member (
                workspace_id, project_id, user_id, role, status, created_by
            ) VALUES (
                #{workspaceId}, #{projectId}, #{userId}, #{role}, 'ACTIVE', #{createdBy}
            )
            """)
    int insertActive(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("userId") long userId,
            @Param("role") String role,
            @Param("createdBy") long createdBy
    );

    @Update("""
            UPDATE dp_project_member
            SET role = #{role},
                version = version + 1
            WHERE workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND user_id = #{userId}
              AND status = 'ACTIVE'
              AND version = #{expectedVersion}
            """)
    int changeRole(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("userId") long userId,
            @Param("role") String role,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_project_member
            SET status = 'REMOVED',
                version = version + 1
            WHERE workspace_id = #{workspaceId}
              AND project_id = #{projectId}
              AND user_id = #{userId}
              AND status = 'ACTIVE'
              AND version = #{expectedVersion}
            """)
    int remove(
            @Param("workspaceId") long workspaceId,
            @Param("projectId") long projectId,
            @Param("userId") long userId,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_project_member
            SET status = 'REMOVED',
                version = version + 1
            WHERE workspace_id = #{workspaceId}
              AND user_id = #{userId}
              AND status = 'ACTIVE'
            """)
    int removeAllForWorkspaceUser(
            @Param("workspaceId") long workspaceId,
            @Param("userId") long userId
    );
}
