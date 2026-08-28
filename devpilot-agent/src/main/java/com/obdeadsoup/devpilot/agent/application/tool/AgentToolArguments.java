package com.obdeadsoup.devpilot.agent.application.tool;

import java.util.Map;

final class AgentToolArguments {
    private AgentToolArguments() {
    }

    static void requireEmpty(Map<String, Object> arguments) {
        if (!arguments.isEmpty()) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
    }

    static int limit(Map<String, Object> arguments) {
        if (arguments.isEmpty()) {
            return 10;
        }
        if (arguments.size() != 1 || !arguments.containsKey("limit")) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
        Object value = arguments.get("limit");
        if (!(value instanceof Number number)) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
        double numeric = number.doubleValue();
        int limit = number.intValue();
        if (!Double.isFinite(numeric) || numeric != limit || limit < 1 || limit > 20) {
            throw new AgentToolException(AgentToolErrorKind.INVALID_ARGUMENT);
        }
        return limit;
    }

    static String bounded(String value, int maxLength) {
        if (value == null) {
            return "";
        }
        return value.length() <= maxLength ? value : value.substring(0, maxLength);
    }
}
