package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.agent.persistence.entity.AgentRunEntity;

/** Java 权威的最小委托上下文；Python 永远不提供 actor、Workspace 或 Project。 */
public record AgentRunExecutionContext(
        String runId,
        String requestId,
        long workspaceId,
        long projectId,
        long createdBy,
        AgentRunStatus status,
        String repositoryFullName,
        String branchName,
        String commitSha
) {
    static AgentRunExecutionContext from(AgentRunEntity entity) {
        return new AgentRunExecutionContext(
                entity.getRunId(), entity.getRequestId(), entity.getWorkspaceId(),
                entity.getProjectId(), entity.getCreatedBy(), AgentRunStatus.valueOf(entity.getStatus()),
                entity.getRepositoryFullName(), entity.getBranchName(), entity.getCommitSha());
    }
}
