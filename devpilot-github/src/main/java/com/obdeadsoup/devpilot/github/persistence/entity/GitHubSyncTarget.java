package com.obdeadsoup.devpilot.github.persistence.entity;

/** 同步 Worker 使用的 Binding 与 Workspace/Project 状态快照，不包含任何真实凭据。 */
public record GitHubSyncTarget(
        long bindingId,
        long workspaceId,
        long projectId,
        long githubRepositoryId,
        String ownerLogin,
        String repositoryName,
        String fullName,
        String apiCredentialRef,
        String bindingStatus,
        boolean bindingDeleted,
        String projectStatus,
        boolean projectDeleted,
        String workspaceStatus,
        boolean workspaceDeleted
) {

    public boolean isEligible() {
        return "ACTIVE".equals(bindingStatus)
                && !bindingDeleted
                && "ACTIVE".equals(projectStatus)
                && !projectDeleted
                && "ACTIVE".equals(workspaceStatus)
                && !workspaceDeleted;
    }
}
