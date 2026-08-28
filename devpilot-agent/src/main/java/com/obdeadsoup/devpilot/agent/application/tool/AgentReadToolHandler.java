package com.obdeadsoup.devpilot.agent.application.tool;

import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContext;

import java.util.Map;

/** allowlist handler 只调用正式 Application Query，不允许接触 Mapper。 */
public interface AgentReadToolHandler {
    AgentToolName name();

    Map<String, Object> execute(AgentRunExecutionContext context, Map<String, Object> arguments);
}
