package com.obdeadsoup.devpilot.agent.application;

import java.util.Optional;

/** Tool Gateway 恢复 run-bound delegation 的内部查询 Port，不对 Browser 暴露。 */
public interface AgentRunExecutionContextQuery {
    Optional<AgentRunExecutionContext> findByRunIdForRuntime(String runId);
}
