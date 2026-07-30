package com.obdeadsoup.devpilot.project.persistence.mapper;

import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceMemberEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.Optional;

@Mapper
public interface WorkspaceMemberMapper {

    @Select("""
            SELECT id,
                   workspace_id AS workspaceId,
                   user_id AS userId,
                   role,
                   status,
                   invited_by AS invitedBy,
                   joined_at AS joinedAt,
                   version
            FROM dp_workspace_member
            WHERE workspace_id = #{workspaceId}
              AND user_id = #{userId}
            """)
    Optional<WorkspaceMemberEntity> findByWorkspaceAndUser(
            @Param("workspaceId") long workspaceId,
            @Param("userId") long userId
    );

    @Insert("""
            INSERT INTO dp_workspace_member (
                workspace_id, user_id, role, status, invited_by
            ) VALUES (
                #{workspaceId}, #{userId}, #{role}, 'INVITED', #{invitedBy}
            )
            """)
    int insertInvitation(
            @Param("workspaceId") long workspaceId,
            @Param("userId") long userId,
            @Param("role") String role,
            @Param("invitedBy") long invitedBy
    );

    @Update("""
            UPDATE dp_workspace_member
            SET status = 'ACTIVE',
                joined_at = CURRENT_TIMESTAMP(6),
                version = version + 1
            WHERE workspace_id = #{workspaceId}
              AND user_id = #{userId}
              AND status = 'INVITED'
              AND version = #{expectedVersion}
            """)
    int activate(
            @Param("workspaceId") long workspaceId,
            @Param("userId") long userId,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_workspace_member
            SET role = #{role},
                version = version + 1
            WHERE workspace_id = #{workspaceId}
              AND user_id = #{userId}
              AND status = 'ACTIVE'
              AND version = #{expectedVersion}
            """)
    int changeRole(
            @Param("workspaceId") long workspaceId,
            @Param("userId") long userId,
            @Param("role") String role,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_workspace_member
            SET status = 'REMOVED',
                version = version + 1
            WHERE workspace_id = #{workspaceId}
              AND user_id = #{userId}
              AND status <> 'REMOVED'
              AND version = #{expectedVersion}
            """)
    int remove(
            @Param("workspaceId") long workspaceId,
            @Param("userId") long userId,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_workspace_member
            SET status = 'REMOVED',
                version = version + 1
            WHERE workspace_id = #{workspaceId}
              AND user_id = #{userId}
              AND status <> 'REMOVED'
            """)
    int removeForNewOwner(
            @Param("workspaceId") long workspaceId,
            @Param("userId") long userId
    );

    @Insert("""
            INSERT INTO dp_workspace_member (
                workspace_id, user_id, role, status, invited_by, joined_at
            ) VALUES (
                #{workspaceId}, #{userId}, 'ADMIN', 'ACTIVE', #{invitedBy}, CURRENT_TIMESTAMP(6)
            )
            ON DUPLICATE KEY UPDATE
                role = 'ADMIN',
                status = 'ACTIVE',
                invited_by = #{invitedBy},
                joined_at = COALESCE(joined_at, CURRENT_TIMESTAMP(6)),
                version = version + 1
            """)
    int upsertActiveAdmin(
            @Param("workspaceId") long workspaceId,
            @Param("userId") long userId,
            @Param("invitedBy") long invitedBy
    );
}
