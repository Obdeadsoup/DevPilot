package com.obdeadsoup.devpilot.agent.application.tool;

import java.util.LinkedHashMap;
import java.util.Map;

/** 已完成并通过大小策略的 Tool result。文本按整体 untrusted 处理。 */
public record AgentToolResult(String resultId, String toolCallId, Map<String, Object> data) {
    public AgentToolResult {
        data = Map.copyOf(new LinkedHashMap<>(data));
    }
}
