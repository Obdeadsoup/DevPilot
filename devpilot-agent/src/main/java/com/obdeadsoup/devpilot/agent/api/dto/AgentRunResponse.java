package com.obdeadsoup.devpilot.agent.api.dto;

import com.obdeadsoup.devpilot.agent.application.AgentRunFailureKind;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.agent.application.AgentRunView;

import java.time.LocalDateTime;

public record AgentRunResponse(
        String runId,
        String requestId,
        long workspaceId,
        long projectId,
        long createdBy,
        AgentRunStatus status,
        String userInput,
        String finalOutput,
        AgentRunFailureKind failureKind,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt,
        LocalDateTime updatedAt,
        long version
) {
    public static AgentRunResponse from(AgentRunView view) {
        return new AgentRunResponse(view.runId(), view.requestId(), view.workspaceId(), view.projectId(),
                view.createdBy(), view.status(), view.userInput(), view.finalOutput(), view.failureKind(),
                view.startedAt(), view.finishedAt(), view.createdAt(), view.updatedAt(), view.version());
    }
}
