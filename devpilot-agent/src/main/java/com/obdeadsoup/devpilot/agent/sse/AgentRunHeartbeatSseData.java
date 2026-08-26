package com.obdeadsoup.devpilot.agent.sse;

/** Heartbeat 不携带 sequence/eventId，也不进入 replay。 */
public record AgentRunHeartbeatSseData(String runId) {
}
