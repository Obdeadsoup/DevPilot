package com.obdeadsoup.devpilot.github.persistence.entity;

public record GitHubRepositoryEntity(
        long id,
        long workspaceId,
        long projectId,
        long githubRepositoryId,
        String fullName,
        String bindingStatus,
        String credentialRef,
        long version
) {
}
