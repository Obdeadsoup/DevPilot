package com.obdeadsoup.devpilot.agent.application.proposal;

import java.time.LocalDateTime;
import java.util.Map;

public record AgentToolProposalView(
        String proposalId, String runId, long actorId, long workspaceId, long projectId,
        String toolCallId, String toolName, Map<String, Object> arguments, String payloadHash,
        String idempotencyKey, AgentToolProposalStatus status, LocalDateTime createdAt,
        LocalDateTime expiresAt, LocalDateTime decisionAt, LocalDateTime executedAt,
        Map<String, Object> executionResult, String resourceId, String failureReason, long version) { }
