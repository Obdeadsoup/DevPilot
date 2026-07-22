package com.obdeadsoup.devpilot.project.persistence.mapper;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface ProjectMapper {

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
