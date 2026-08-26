package com.obdeadsoup.devpilot.agent.application;

/** Coordinator 面向浏览器事件通道的 Port；实现可以是本机有界 SSE Hub。 */
public interface AgentRunEventPublisher {
    /** 在 RPC 启动前建立已知 run 缓存，区分首次连接与 Java 重启后的 replay gap。 */
    void initialize(String runId);

    void publish(AgentStreamEvent event);
}
