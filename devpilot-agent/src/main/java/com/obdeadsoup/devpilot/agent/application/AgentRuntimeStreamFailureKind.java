package com.obdeadsoup.devpilot.agent.application;

/** 异步 Streaming Port 向 Core 报告的稳定传输失败，不暴露 gRPC Status 或 description。 */
public enum AgentRuntimeStreamFailureKind {
    DEADLINE_EXCEEDED,
    UNAVAILABLE,
    INVALID_ARGUMENT,
    INTERNAL,
    UNKNOWN,
    PROTOCOL,
    CIRCUIT_OPEN,
    CAPACITY_REJECTED,
    USER_CANCELLED
}
