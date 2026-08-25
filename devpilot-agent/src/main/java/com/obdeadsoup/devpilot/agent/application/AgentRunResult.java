package com.obdeadsoup.devpilot.agent.application;

import java.util.Objects;

/** 不携带 generated protobuf DTO 的内部 Agent Run 结果。 */
public record AgentRunResult(String runId, String finalOutput, AgentRunStatus status) {

    public AgentRunResult {
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(finalOutput, "finalOutput must not be null");
        Objects.requireNonNull(status, "status must not be null");
        if (runId.isBlank()) {
            throw new IllegalArgumentException("runId must not be blank");
        }
    }
}
