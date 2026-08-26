package com.obdeadsoup.devpilot.agent.application;

/** 单次 Agent Run 的 Java 侧关联标识。 */
public record AgentRunIdentity(String requestId, String runId) {
}
