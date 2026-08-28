package com.obdeadsoup.devpilot.agent.application.tool;

import java.util.Map;

/** protobuf-free 的 Tool 调用命令；身份与 scope 刻意不在请求模型中。 */
public record AgentToolCommand(
        String requestId,
        String runId,
        String toolCallId,
        String toolName,
        Map<String, Object> arguments
) {
}
