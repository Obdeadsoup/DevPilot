package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import java.util.Locale;
import java.util.Objects;

/** 脱敏后的 Agent Runtime RPC 异常；底层 Status 仅通过 cause 留给本地诊断。 */
public final class AgentRuntimeClientException extends RuntimeException {

    private final AgentRuntimeFailureKind kind;

    public AgentRuntimeClientException(AgentRuntimeFailureKind kind, Throwable cause) {
        super(sanitizedMessage(kind), cause);
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    public AgentRuntimeClientException(AgentRuntimeFailureKind kind) {
        super(sanitizedMessage(kind));
        this.kind = Objects.requireNonNull(kind, "kind must not be null");
    }

    public AgentRuntimeFailureKind kind() {
        return kind;
    }

    private static String sanitizedMessage(AgentRuntimeFailureKind kind) {
        Objects.requireNonNull(kind, "kind must not be null");
        return "agent runtime RPC failed: " + kind.name().toLowerCase(Locale.ROOT);
    }
}
