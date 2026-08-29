package com.obdeadsoup.devpilot.agent.application;

/** Java Application 启动 Python Server Streaming 的异步出站 Port。 */
public interface AgentRuntimeStreamingPort {
    /** 发起后立即返回；后续事件、失败和完成由 listener 接收。 */
    AgentRuntimeStreamHandle stream(AgentRunCommand command, AgentRuntimeEventListener listener);
}
