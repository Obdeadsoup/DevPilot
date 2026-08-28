package com.obdeadsoup.devpilot.agent.application.tool;

import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContext;
import com.obdeadsoup.devpilot.task.api.dto.TaskResponse;
import com.obdeadsoup.devpilot.task.application.TaskQueryService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 开放 Task Tool：limit 在进入 SQL 前限制为 1..20，且不返回 description/history。 */
@Component
public final class ListOpenTasksToolHandler implements AgentReadToolHandler {
    private static final int MAX_TITLE_LENGTH = 500;
    private final TaskQueryService taskQueryService;

    public ListOpenTasksToolHandler(TaskQueryService taskQueryService) {
        this.taskQueryService = taskQueryService;
    }

    @Override
    public AgentToolName name() {
        return AgentToolName.TASK_LIST_OPEN;
    }

    @Override
    public Map<String, Object> execute(AgentRunExecutionContext context, Map<String, Object> arguments) {
        int limit = AgentToolArguments.limit(arguments);
        List<Map<String, Object>> items = taskQueryService.listOpenForActor(
                        context.createdBy(), context.workspaceId(), context.projectId(), limit)
                .stream().map(this::item).toList();
        return Map.of(
                "items", items,
                "count", items.size(),
                "external_untrusted_content", true);
    }

    private Map<String, Object> item(TaskResponse task) {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("taskId", task.id());
        item.put("key", task.displayKey());
        item.put("title", AgentToolArguments.bounded(task.title(), MAX_TITLE_LENGTH));
        item.put("status", task.status().name());
        item.put("priority", task.priority().name());
        if (task.assigneeUserId() != null) {
            item.put("assigneeId", task.assigneeUserId());
        }
        if (task.dueAt() != null) {
            item.put("dueAt", task.dueAt().toString());
        }
        item.put("externalUntrustedContent", true);
        return item;
    }
}
