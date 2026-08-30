package com.obdeadsoup.devpilot.identity.persistence.mapper;

import com.obdeadsoup.devpilot.identity.persistence.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface UserMapper {

    @Insert("""
            INSERT INTO dp_user (username, email, display_name, password_hash, status)
            VALUES (#{username}, #{email}, #{displayName}, #{passwordHash}, 'ACTIVE')
            """)
    int insert(
            @Param("username") String username,
            @Param("email") String email,
            @Param("displayName") String displayName,
            @Param("passwordHash") String passwordHash
    );

    @Select("""
            SELECT EXISTS(SELECT 1 FROM dp_user WHERE username = #{username} AND deleted = 0)
            """)
    boolean existsByUsername(@Param("username") String username);

    @Select("""
            SELECT EXISTS(SELECT 1 FROM dp_user WHERE email = #{email} AND deleted = 0)
            """)
    boolean existsByEmail(@Param("email") String email);

    @Select("""
            SELECT id,
                   username,
                   email,
                   display_name AS displayName,
                   password_hash AS passwordHash,
                   status,
                   created_at AS createdAt,
                   updated_at AS updatedAt,
                   version,
                   deleted
            FROM dp_user
            WHERE deleted = 0
              AND (username = #{normalizedLogin} OR email = #{normalizedLogin})
            ORDER BY CASE WHEN username = #{normalizedLogin} THEN 0 ELSE 1 END
            LIMIT 1
            """)
    Optional<UserEntity> findByNormalizedLogin(@Param("normalizedLogin") String normalizedLogin);

    @Select("""
            SELECT id, username, email, display_name AS displayName, password_hash AS passwordHash,
                   status, created_at AS createdAt, updated_at AS updatedAt, version, deleted
            FROM dp_user
            WHERE LOWER(email) = LOWER(#{email}) AND status = 'ACTIVE' AND deleted = 0
            LIMIT 1
            """)
    Optional<UserEntity> findActiveByEmail(@Param("email") String email);

    @Select("""
            SELECT COUNT(*)
            FROM dp_user
            WHERE id = #{userId}
              AND status = 'ACTIVE'
              AND deleted = 0
            """)
    int countActiveById(@Param("userId") long userId);
}
