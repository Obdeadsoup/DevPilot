package com.obdeadsoup.devpilot.agent.application.tool;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * protobuf-free 的第一道结果大小防线；gRPC Adapter 还会按 Struct 实际 serialized size 二次校验。
 */
public final class AgentToolResultSizePolicy {
    private final int maxBytes;

    public AgentToolResultSizePolicy(int maxBytes) {
        if (maxBytes < 1) {
            throw new IllegalArgumentException("maxBytes must be positive");
        }
        this.maxBytes = maxBytes;
    }

    public int maxBytes() {
        return maxBytes;
    }

    public void requireWithinLimit(Map<String, Object> result) {
        if (estimate(result) > maxBytes) {
            throw new AgentToolException(AgentToolErrorKind.RESULT_TOO_LARGE);
        }
    }

    private long estimate(Object value) {
        if (value == null) {
            return 4;
        }
        if (value instanceof String text) {
            return text.getBytes(StandardCharsets.UTF_8).length + 8L;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return 32;
        }
        if (value instanceof Map<?, ?> map) {
            long size = 16;
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                if (!(entry.getKey() instanceof String key)) {
                    throw new AgentToolException(AgentToolErrorKind.INTERNAL);
                }
                size += estimate(key) + estimate(entry.getValue());
                if (size > maxBytes) {
                    return size;
                }
            }
            return size;
        }
        if (value instanceof List<?> list) {
            long size = 16;
            for (Object item : list) {
                size += estimate(item);
                if (size > maxBytes) {
                    return size;
                }
            }
            return size;
        }
        throw new AgentToolException(AgentToolErrorKind.INTERNAL);
    }
}
