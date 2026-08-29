package com.obdeadsoup.devpilot.agent.application;

/** Java Core 发往 Python Runtime 的取消命令。 */
public record AgentRuntimeCancelCommand(String runId, String requestId) {
}
