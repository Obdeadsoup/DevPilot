package com.obdeadsoup.devpilot.agent.application;

/** Java Core 可理解的 Agent 生命周期事件类型，不依赖 protobuf enum。 */
public enum AgentStreamEventType {
    RUN_STARTED,
    MODEL_STEP_STARTED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    RUN_SUCCEEDED,
    RUN_FAILED,
    RUN_CANCELLED,
    RUN_WAITING_APPROVAL,
    RUN_RESUMED;

    public boolean isTerminal() {
        return this == RUN_SUCCEEDED || this == RUN_FAILED || this == RUN_CANCELLED
                || this == RUN_WAITING_APPROVAL;
    }

    /** WAITING_APPROVAL 结束当前 Runtime RPC，但仍保留浏览器 SSE 等待后续恢复事件。 */
    public boolean isRunTerminal() {
        return this == RUN_SUCCEEDED || this == RUN_FAILED || this == RUN_CANCELLED;
    }
}
