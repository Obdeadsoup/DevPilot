package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

/** Java 侧稳定 RPC 失败分类；不向 Application Core 扩散 gRPC Status 类型。 */
public enum AgentRuntimeFailureKind {
    DEADLINE_EXCEEDED,
    UNAVAILABLE,
    INVALID_ARGUMENT,
    INTERNAL,
    UNKNOWN,
    PROTOCOL
}
