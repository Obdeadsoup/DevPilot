package com.obdeadsoup.devpilot.agent.application.tool;

import java.util.Arrays;
import java.util.Optional;

/** Tool wire name 的显式 allowlist；它不是 Spring Bean、Java 类名或可反射的方法名。 */
public enum AgentToolName {
    PROJECT_GET_SUMMARY("project.get_summary"),
    TASK_LIST_OPEN("task.list_open"),
    PROJECT_LIST_RECENT_ACTIVITY("project.list_recent_activity");

    private final String wireName;

    AgentToolName(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static Optional<AgentToolName> fromWireName(String value) {
        return Arrays.stream(values()).filter(item -> item.wireName.equals(value)).findFirst();
    }
}
