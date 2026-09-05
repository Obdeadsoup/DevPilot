package com.obdeadsoup.devpilot.agent.api.dto;

import com.obdeadsoup.devpilot.agent.application.proposal.AgentToolProposalStatus;
import com.obdeadsoup.devpilot.agent.application.proposal.AgentToolProposalView;
import java.time.LocalDateTime;
import java.util.Map;

public record AgentToolProposalResponse(
        String proposalId, String runId, String toolName, Map<String, Object> arguments,
        AgentToolProposalStatus status, LocalDateTime createdAt, LocalDateTime expiresAt,
        LocalDateTime decisionAt, LocalDateTime executedAt, String resourceId, String failureReason) {
    public static AgentToolProposalResponse from(AgentToolProposalView value) {
        return new AgentToolProposalResponse(value.proposalId(), value.runId(), value.toolName(), value.arguments(),
                value.status(), value.createdAt(), value.expiresAt(), value.decisionAt(), value.executedAt(),
                value.resourceId(), value.failureReason());
    }
}
