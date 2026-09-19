package com.obdeadsoup.devpilot.agent.application;

/** Authorized identity for scoped Agent memory; business Tool authorization remains in Java. */
public record AgentExecutionScope(long workspaceId, long projectId, long actorUserId) {
    public AgentExecutionScope {
        if (workspaceId <= 0 || projectId <= 0 || actorUserId <= 0) {
            throw new IllegalArgumentException("Agent execution scope IDs must be positive");
        }
    }
}
