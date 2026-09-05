package com.obdeadsoup.devpilot.agent.application.tool;

import java.util.Arrays;
import java.util.Optional;

/** Tool wire name 的显式 allowlist；它不是 Spring Bean、Java 类名或可反射的方法名。 */
public enum AgentToolName {
    PROJECT_GET_SUMMARY("project.get_summary", AgentToolRisk.READ_ONLY),
    TASK_LIST_OPEN("task.list_open", AgentToolRisk.READ_ONLY),
    PROJECT_LIST_RECENT_ACTIVITY("project.list_recent_activity", AgentToolRisk.READ_ONLY),
    TASK_CREATE("task.create", AgentToolRisk.WRITE_REQUIRES_APPROVAL);

    private final String wireName;
    private final AgentToolRisk risk;

    AgentToolName(String wireName, AgentToolRisk risk) {
        this.wireName = wireName;
        this.risk = risk;
    }

    public String wireName() {
        return wireName;
    }

    public AgentToolRisk risk() { return risk; }

    public static Optional<AgentToolName> fromWireName(String value) {
        return Arrays.stream(values()).filter(item -> item.wireName.equals(value)).findFirst();
    }
}
