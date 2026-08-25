package com.obdeadsoup.devpilot.agent.application;

/**
 * Java Application 调用独立 Python Agent Runtime 的出站 Port。
 *
 * <p>Application Service 只依赖本接口，不直接依赖 generated Stub、Channel 或 gRPC Status。</p>
 */
public interface AgentRuntimePort {

    /**
     * 同步执行一次 Unary StartRun。
     *
     * @throws RuntimeException 当传输、Deadline 或远端边界失败时，由 Adapter 抛出稳定分类错误
     */
    AgentRunResult run(AgentRunCommand command);
}
