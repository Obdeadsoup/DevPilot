package com.obdeadsoup.devpilot.agent.application;

import java.time.LocalDateTime;

/** 历史列表的轻量投影；全文输入和输出只能通过单条详情接口读取。 */
public record AgentRunHistoryItem(
        String runId,
        AgentRunStatus status,
        AgentRunFailureKind failureKind,
        LocalDateTime startedAt,
        LocalDateTime finishedAt,
        LocalDateTime createdAt
) {
    public static AgentRunHistoryItem from(AgentRunView view) {
        return new AgentRunHistoryItem(
                view.runId(), view.status(), view.failureKind(), view.startedAt(),
                view.finishedAt(), view.createdAt()
        );
    }
}
