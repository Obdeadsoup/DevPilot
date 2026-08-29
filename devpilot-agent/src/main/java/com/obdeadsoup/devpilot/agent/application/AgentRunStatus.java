package com.obdeadsoup.devpilot.agent.application;

/** Agent Runtime 对 Java Application Core 暴露的最小同步结果状态。 */
public enum AgentRunStatus {
    RUNNING,
    SUCCEEDED,
    FAILED,
    CANCELLED
}
