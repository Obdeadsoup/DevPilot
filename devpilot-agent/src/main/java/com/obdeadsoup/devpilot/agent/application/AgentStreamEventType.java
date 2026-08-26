package com.obdeadsoup.devpilot.agent.application;

/** Java Core 可理解的 Agent 生命周期事件类型，不依赖 protobuf enum。 */
public enum AgentStreamEventType {
    RUN_STARTED,
    MODEL_STEP_STARTED,
    TOOL_STARTED,
    TOOL_COMPLETED,
    RUN_SUCCEEDED,
    RUN_FAILED;

    public boolean isTerminal() {
        return this == RUN_SUCCEEDED || this == RUN_FAILED;
    }
}
