package com.obdeadsoup.devpilot.agent.application;

import com.obdeadsoup.devpilot.agent.infrastructure.grpc.AgentRuntimeFailureKind;

/** Java 业务投影持久化的稳定失败分类；不会保存远端原始异常或 gRPC 描述。 */
public enum AgentRunFailureKind {
    REMOTE_FAILED,
    DEADLINE_EXCEEDED,
    UNAVAILABLE,
    INVALID_ARGUMENT,
    INTERNAL,
    UNKNOWN,
    PROTOCOL;

    public static AgentRunFailureKind fromRuntime(AgentRuntimeFailureKind kind) {
        return valueOf(kind.name());
    }
}
