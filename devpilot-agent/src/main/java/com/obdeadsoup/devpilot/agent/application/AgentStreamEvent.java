package com.obdeadsoup.devpilot.agent.application;

import java.util.Objects;

/** protobuf AgentEvent 映射后的内部事件；字段语义由 Coordinator 统一校验。 */
public record AgentStreamEvent(
        String eventId,
        String runId,
        long sequence,
        AgentStreamEventType type,
        int step,
        String toolName,
        String finalOutput,
        String failureKind
) {
    public AgentStreamEvent {
        Objects.requireNonNull(eventId, "eventId must not be null");
        Objects.requireNonNull(runId, "runId must not be null");
        Objects.requireNonNull(type, "type must not be null");
    }
}
