package com.obdeadsoup.devpilot.agent.application.tool;

import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContext;
import com.obdeadsoup.devpilot.project.api.dto.ProjectResponse;
import com.obdeadsoup.devpilot.project.application.ProjectService;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;

/** Project 摘要 Tool：显式 actor 查询 Project Application Service，不访问 Project Mapper。 */
@Component
public final class ProjectSummaryToolHandler implements AgentReadToolHandler {
    private static final int MAX_DESCRIPTION_LENGTH = 2_000;
    private final ProjectService projectService;

    public ProjectSummaryToolHandler(ProjectService projectService) {
        this.projectService = projectService;
    }

    @Override
    public AgentToolName name() {
        return AgentToolName.PROJECT_GET_SUMMARY;
    }

    @Override
    public Map<String, Object> execute(AgentRunExecutionContext context, Map<String, Object> arguments) {
        AgentToolArguments.requireEmpty(arguments);
        ProjectResponse project = projectService.getProjectForActor(
                context.createdBy(), context.workspaceId(), context.projectId());
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("projectId", project.id());
        result.put("projectKey", project.projectKey());
        result.put("name", project.name());
        result.put("status", project.status().name());
        result.put("visibility", project.visibility().name());
        result.put("description", AgentToolArguments.bounded(project.description(), MAX_DESCRIPTION_LENGTH));
        result.put("external_untrusted_content", true);
        return result;
    }
}
