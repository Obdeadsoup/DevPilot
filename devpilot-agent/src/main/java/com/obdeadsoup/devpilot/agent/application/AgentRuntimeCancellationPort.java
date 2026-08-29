package com.obdeadsoup.devpilot.agent.application;

/** Python Runtime 取消边界。只有 ACCEPTED 才能驱动 Java 投影进入 CANCELLED。 */
public interface AgentRuntimeCancellationPort {
    AgentRuntimeCancelStatus cancel(AgentRuntimeCancelCommand command);
}
