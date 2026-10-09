package com.obdeadsoup.devpilot.agent.application.tool;

import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContext;
import com.obdeadsoup.devpilot.github.application.GitHubRepositoryBindingService;
import org.springframework.stereotype.Component;

import java.util.Map;

/** Internal read operation: Run context + current binding + live repository RBAC, without secrets. */
@Component
public final class ProjectGitHubBindingToolHandler implements AgentReadToolHandler {
    private final GitHubRepositoryBindingService bindingService;

    public ProjectGitHubBindingToolHandler(GitHubRepositoryBindingService bindingService) {
        this.bindingService = bindingService;
    }

    @Override
    public AgentToolName name() { return AgentToolName.PROJECT_GET_GITHUB_BINDING; }

    @Override
    public Map<String, Object> execute(AgentRunExecutionContext context, Map<String, Object> arguments) {
        AgentToolArguments.requireEmpty(arguments);
        var scope = bindingService.resolveReadScopeForAgent(
                context.createdBy(), context.workspaceId(), context.projectId(),
                context.repositoryFullName(), context.branchName());
        String[] identity = scope.repositoryFullName().split("/", -1);
        if (identity.length != 2 || identity[0].isBlank() || identity[1].isBlank()) {
            throw new AgentToolException(AgentToolErrorKind.PROTOCOL);
        }
        return Map.of("owner", identity[0], "repo", identity[1], "branch", scope.branchName(),
                "external_untrusted_content", true);
    }
}
