package com.obdeadsoup.devpilot.github.persistence.mapper;

import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.Optional;

@Mapper
public interface GitHubRepositoryMapper {

    @Select("""
            SELECT id,
                   workspace_id AS workspaceId,
                   project_id AS projectId,
                   github_repository_id AS githubRepositoryId,
                   full_name AS fullName,
                   binding_status AS bindingStatus,
                   credential_ref AS credentialRef,
                   version
            FROM dp_github_repository
            WHERE github_repository_id = #{githubRepositoryId} AND deleted = 0
            """)
    Optional<GitHubRepositoryEntity> findByGitHubRepositoryId(
            @Param("githubRepositoryId") long githubRepositoryId
    );
}
