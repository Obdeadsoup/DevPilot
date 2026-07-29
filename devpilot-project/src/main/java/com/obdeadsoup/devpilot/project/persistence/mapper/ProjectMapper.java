package com.obdeadsoup.devpilot.project.persistence.mapper;

import com.obdeadsoup.devpilot.project.persistence.entity.ProjectEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface ProjectMapper {

    @Select("""
            SELECT id,
                   workspace_id AS workspaceId,
                   status,
                   visibility,
                   version,
                   deleted
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
}
