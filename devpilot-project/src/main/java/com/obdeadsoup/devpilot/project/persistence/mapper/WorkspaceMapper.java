package com.obdeadsoup.devpilot.project.persistence.mapper;

import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceEntity;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.util.List;
import java.util.Optional;

@Mapper
public interface WorkspaceMapper {

    String COLUMNS = """
            id,
            name,
            slug,
            description,
            owner_user_id AS ownerUserId,
            status,
            version,
            created_at AS createdAt,
            updated_at AS updatedAt,
            deleted
            """;

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_workspace
            WHERE id = #{workspaceId}
              AND deleted = 0
            """)
    Optional<WorkspaceEntity> findById(@Param("workspaceId") long workspaceId);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_workspace
            WHERE slug = #{slug}
              AND deleted = 0
            """)
    Optional<WorkspaceEntity> findBySlug(@Param("slug") String slug);

    @Insert("""
            INSERT INTO dp_workspace (
                name, slug, description, owner_user_id, status, version
            ) VALUES (
                #{name}, #{slug}, #{description}, #{ownerUserId}, 'ACTIVE', 0
            )
            """)
    int insert(
            @Param("name") String name,
            @Param("slug") String slug,
            @Param("description") String description,
            @Param("ownerUserId") long ownerUserId
    );

    @Select("""
            SELECT COUNT(*)
            FROM dp_workspace w
            WHERE w.deleted = 0
              AND (
                w.owner_user_id = #{userId}
                OR (
                    w.status = 'ACTIVE'
                    AND EXISTS (
                        SELECT 1
                        FROM dp_workspace_member wm
                        WHERE wm.workspace_id = w.id
                          AND wm.user_id = #{userId}
                          AND wm.status = 'ACTIVE'
                    )
                )
              )
            """)
    long countMine(@Param("userId") long userId);

    @Select("""
            SELECT
            """ + COLUMNS + """
            FROM dp_workspace w
            WHERE w.deleted = 0
              AND (
                w.owner_user_id = #{userId}
                OR (
                    w.status = 'ACTIVE'
                    AND EXISTS (
                        SELECT 1
                        FROM dp_workspace_member wm
                        WHERE wm.workspace_id = w.id
                          AND wm.user_id = #{userId}
                          AND wm.status = 'ACTIVE'
                    )
                )
              )
            ORDER BY w.updated_at DESC, w.id DESC
            LIMIT #{offset}, #{size}
            """)
    List<WorkspaceEntity> findMine(
            @Param("userId") long userId,
            @Param("offset") long offset,
            @Param("size") int size
    );

    @Update("""
            UPDATE dp_workspace
            SET name = #{name},
                description = #{description},
                version = version + 1
            WHERE id = #{workspaceId}
              AND version = #{expectedVersion}
              AND status = 'ACTIVE'
              AND deleted = 0
            """)
    int updateProfile(
            @Param("workspaceId") long workspaceId,
            @Param("name") String name,
            @Param("description") String description,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_workspace
            SET status = 'DISABLED',
                version = version + 1
            WHERE id = #{workspaceId}
              AND owner_user_id = #{ownerUserId}
              AND version = #{expectedVersion}
              AND status = 'ACTIVE'
              AND deleted = 0
            """)
    int disable(
            @Param("workspaceId") long workspaceId,
            @Param("ownerUserId") long ownerUserId,
            @Param("expectedVersion") long expectedVersion
    );

    @Update("""
            UPDATE dp_workspace
            SET status = 'ACTIVE',
                version = version + 1
            WHERE id = #{workspaceId}
              AND owner_user_id = #{ownerUserId}
              AND version = #{expectedVersion}
              AND status = 'DISABLED'
              AND deleted = 0
            """)
    int reactivate(
            @Param("workspaceId") long workspaceId,
            @Param("ownerUserId") long ownerUserId,
            @Param("expectedVersion") long expectedVersion
    );

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
