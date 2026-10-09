package com.obdeadsoup.devpilot.agent.application.tool;

import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContext;
import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContextQuery;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.github.application.GitHubRepositoryBindingService;
import com.obdeadsoup.devpilot.github.application.GitHubRepositoryBranchSnapshot;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

class ProjectGitHubBindingToolHandlerTest {
    private final GitHubRepositoryBindingService bindings = mock(GitHubRepositoryBindingService.class);
    private final ProjectGitHubBindingToolHandler handler = new ProjectGitHubBindingToolHandler(bindings);
    private final AgentRunExecutionContext context = new AgentRunExecutionContext(
            "run-1", "request-1", 11, 22, 33, AgentRunStatus.RUNNING,
            "Obdeadsoup/DevPilot", "agent", "a".repeat(40));

    @Test
    void gatewayRestoresRunScopeAndReturnsOnlyCredentialFreeBinding() {
        AgentRunExecutionContextQuery query = mock(AgentRunExecutionContextQuery.class);
        when(query.findByRunIdForRuntime("run-1")).thenReturn(Optional.of(context));
        when(bindings.resolveReadScopeForAgent(33, 11, 22, "Obdeadsoup/DevPilot", "agent"))
                .thenReturn(new GitHubRepositoryBranchSnapshot("Obdeadsoup/DevPilot", "agent", null));
        var gateway = new AgentToolApplicationService(query, List.of(handler),
                new AgentToolResultSizePolicy(65_536));
        var result = gateway.execute(new AgentToolCommand(
                "request-1", "run-1", "call-1", "project.get_github_binding", Map.of()));
        assertThat(result.data()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "owner", "Obdeadsoup", "repo", "DevPilot", "branch", "agent",
                "external_untrusted_content", true));
        verify(bindings).resolveReadScopeForAgent(33, 11, 22, "Obdeadsoup/DevPilot", "agent");
    }

    @Test
    void modelScopeArgumentsAreNeverAccepted() {
        assertThatThrownBy(() -> handler.execute(context, Map.of("repo", "evil")))
                .isInstanceOf(AgentToolException.class);
        verifyNoInteractions(bindings);
    }

    @Test
    void inactiveRunCannotResolveBinding() {
        AgentRunExecutionContextQuery query = mock(AgentRunExecutionContextQuery.class);
        when(query.findByRunIdForRuntime("run-1")).thenReturn(Optional.of(
                new AgentRunExecutionContext("run-1", "request-1", 11, 22, 33,
                        AgentRunStatus.CANCELLED, "Obdeadsoup/DevPilot", "agent", null)));
        var gateway = new AgentToolApplicationService(query, List.of(handler),
                new AgentToolResultSizePolicy(65_536));
        assertThatThrownBy(() -> gateway.execute(new AgentToolCommand(
                "request-1", "run-1", "call-1", "project.get_github_binding", Map.of())))
                .isInstanceOfSatisfying(AgentToolException.class,
                        error -> assertThat(error.kind()).isEqualTo(AgentToolErrorKind.RUN_NOT_ACTIVE));
        verifyNoInteractions(bindings);
    }
}
