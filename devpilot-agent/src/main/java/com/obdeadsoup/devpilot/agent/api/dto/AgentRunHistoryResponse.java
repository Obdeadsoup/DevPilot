package com.obdeadsoup.devpilot.agent.api.dto;

import com.obdeadsoup.devpilot.agent.application.AgentRunFailureKind;
import com.obdeadsoup.devpilot.agent.application.AgentRunHistoryItem;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;

import java.time.LocalDateTime;

/** Agent 历史列表响应，故意不包含用户输入和模型输出全文。 */
public record AgentRunHistoryResponse(
        String runId,
        String branchName,
        String commitSha,
        AgentRunStatus status,
        AgentRunFailureKind failureKind,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
) {
    public static AgentRunHistoryResponse from(AgentRunHistoryItem item) {
        return new AgentRunHistoryResponse(item.runId(), item.branchName(), item.commitSha(), item.status(), item.failureKind(),
                item.startedAt(), item.finishedAt(), item.createdAt());
    }
}
