package com.obdeadsoup.devpilot.agent.application.tool;

/** Tool 内部稳定异常；message 固定为 kind，避免把 SQL、参数或底层异常带入 RPC。 */
public final class AgentToolException extends RuntimeException {
    private final AgentToolErrorKind kind;

    public AgentToolException(AgentToolErrorKind kind) {
        super(kind.name());
        this.kind = kind;
    }

    public AgentToolErrorKind kind() {
        return kind;
    }
}
