package com.obdeadsoup.devpilot.agent.application;

/** Core 持有的异步流控制句柄；cancel 只关闭本地传输，不代表远端业务已取消。 */
@FunctionalInterface
public interface AgentRuntimeStreamHandle {
    AgentRuntimeStreamHandle NOOP = () -> { };

    void cancel();
}
