package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.agent.persistence.entity.AgentRunEntity;

import java.time.LocalDateTime;

/** AgentRun 对 HTTP 层暴露的只读业务投影，不泄漏持久化实体。 */
public record AgentRunView(
        String runId,
        String requestId,
        long workspaceId,
        long projectId,
        long createdBy,
        AgentRunStatus status,
        String userInput,
        String repositoryFullName,
        String branchName,
        String commitSha,
        String finalOutput,
        AgentRunFailureKind failureKind,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version
) {
    public static AgentRunView from(AgentRunEntity entity) {
        return new AgentRunView(
                entity.getRunId(),
                entity.getRequestId(),
                entity.getWorkspaceId(),
                entity.getProjectId(),
                entity.getCreatedBy(),
                AgentRunStatus.valueOf(entity.getStatus()),
                entity.getUserInput(),
                entity.getRepositoryFullName(),
                entity.getBranchName(),
                entity.getCommitSha(),
                entity.getFinalOutput(),
                entity.getFailureKind() == null ? null : AgentRunFailureKind.valueOf(entity.getFailureKind()),
                entity.getStartedAt(),
                entity.getFinishedAt(),
                entity.getCreatedAt(),
                entity.getUpdatedAt(),
                entity.getVersion()
        );
    }
}
