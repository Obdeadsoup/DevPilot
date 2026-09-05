package com.obdeadsoup.devpilot.agent.application.proposal;

import java.util.Map;

public record CreateAgentToolProposalCommand(
        String requestId, String runId, String toolCallId, String toolName, Map<String, Object> arguments) { }
