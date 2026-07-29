package com.obdeadsoup.devpilot.identity.persistence.mapper;

import com.obdeadsoup.devpilot.identity.persistence.entity.WorkspaceEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

@Mapper
public interface WorkspaceMapper {

    @Select("""
            SELECT id,
                   owner_user_id AS ownerUserId,
                   status,
                   version,
                   deleted
            FROM dp_workspace
            WHERE id = #{workspaceId}
              AND deleted = 0
            """)
    Optional<WorkspaceEntity> findById(@Param("workspaceId") long workspaceId);

    @Update("""
            UPDATE dp_workspace
            SET owner_user_id = #{newOwnerUserId},
                version = version + 1
            WHERE id = #{workspaceId}
              AND owner_user_id = #{currentOwnerUserId}
              AND version = #{expectedVersion}
              AND status = 'ACTIVE'
              AND deleted = 0
            """)
    int transferOwnership(
            @Param("workspaceId") long workspaceId,
            @Param("currentOwnerUserId") long currentOwnerUserId,
            @Param("newOwnerUserId") long newOwnerUserId,
            @Param("expectedVersion") long expectedVersion
    );
}
