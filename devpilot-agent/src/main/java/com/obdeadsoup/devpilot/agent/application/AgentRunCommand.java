package com.obdeadsoup.devpilot.agent.application;

import java.util.Objects;

/**
 * Java Application Core 发起同步 Agent Run 的内部命令。
 *
 * <p>它不依赖 protobuf；requestId/runId 由 Java 边界生成，userInput 才会进入 Python Runtime。</p>
 */
public record AgentRunCommand(String requestId, String runId, String userInput) {

    public AgentRunCommand {
        requireNonBlank(requestId, "requestId");
        requireNonBlank(runId, "runId");
        requireNonBlank(userInput, "userInput");
    }

    private static void requireNonBlank(String value, String fieldName) {
        Objects.requireNonNull(value, fieldName + " must not be null");
        if (value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
    }
}
