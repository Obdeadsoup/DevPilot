package com.obdeadsoup.devpilot.identity.persistence.mapper;

import com.obdeadsoup.devpilot.identity.persistence.entity.UserEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface UserMapper {

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
}
