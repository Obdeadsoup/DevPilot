package com.obdeadsoup.devpilot.agent.application.tool;

import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContext;
import com.obdeadsoup.devpilot.project.api.dto.ProjectActivityResponse;
import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 近期 Activity Tool：只投影有限字段，GitHub/用户文本整体标记为 untrusted。 */
@Component
public final class RecentProjectActivityToolHandler implements AgentReadToolHandler {
    private static final int MAX_TEXT_LENGTH = 2_000;
    private final ProjectActivityService activityService;

    public RecentProjectActivityToolHandler(ProjectActivityService activityService) {
        this.activityService = activityService;
    }

    @Override
    public AgentToolName name() {
        return AgentToolName.PROJECT_LIST_RECENT_ACTIVITY;
    }

    @Override
    public Map<String, Object> execute(AgentRunExecutionContext context, Map<String, Object> arguments) {
        int limit = AgentToolArguments.limit(arguments);
        List<Map<String, Object>> items = activityService.queryTimelineForActor(
                        context.createdBy(), context.workspaceId(), context.projectId(), 1, limit)
                .items().stream().map(this::item).toList();
        return Map.of(
                "items", items,
                "count", items.size(),
                "external_untrusted_content", true);
    }

    private Map<String, Object> item(ProjectActivityResponse activity) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("activityType", activity.activityType());
        item.put("sourceType", activity.sourceType());
        item.put("title", AgentToolArguments.bounded(activity.title(), MAX_TEXT_LENGTH));
        item.put("summary", AgentToolArguments.bounded(activity.summary(), MAX_TEXT_LENGTH));
        item.put("occurredAt", activity.occurredAt().toString());
        item.put("externalUntrustedContent", true);
        return item;
    }
}
