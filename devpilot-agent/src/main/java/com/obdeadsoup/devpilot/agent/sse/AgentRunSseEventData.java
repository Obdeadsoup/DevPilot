package com.obdeadsoup.devpilot.agent.sse;

import com.obdeadsoup.devpilot.agent.application.AgentStreamEvent;

/** 浏览器专用 SSE DTO，不暴露 protobuf generated message。 */
public record AgentRunSseEventData(
        String runId,
        long sequence,
        int step,
        String toolName,
        String finalOutput,
        String failureKind,
        String proposalId,
        String proposalExpiresAt
) {
    static AgentRunSseEventData from(AgentStreamEvent event) {
        return new AgentRunSseEventData(event.runId(), event.sequence(), event.step(), event.toolName(),
                event.finalOutput(), event.failureKind(), event.proposalId(), event.proposalExpiresAt());
    }
}
