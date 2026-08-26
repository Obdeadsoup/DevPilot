package com.obdeadsoup.devpilot.agent.sse;

/** 告知客户端事件缓存不能完整恢复，必须用 scoped GET 读取权威 AgentRun 状态。 */
public record AgentRunReplayGapSseData(String runId, boolean fetchAuthoritativeRun) {
}
