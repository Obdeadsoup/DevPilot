package com.obdeadsoup.devpilot.agent.application.tool;

import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContext;
import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContextQuery;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class AgentToolApplicationServiceTest {
    private final AgentRunExecutionContextQuery contextQuery = mock(AgentRunExecutionContextQuery.class);
    private final AgentReadToolHandler handler = new AgentReadToolHandler() {
        @Override
        public AgentToolName name() {
            return AgentToolName.PROJECT_GET_SUMMARY;
        }

        @Override
        public Map<String, Object> execute(AgentRunExecutionContext context,
                                           Map<String, Object> arguments) {
            return Map.of("projectId", context.projectId(), "external_untrusted_content", true);
        }
    };

    @Test
    void restoresAuthoritativeContextAndDispatchesAllowlistedTool() {
        when(contextQuery.findByRunIdForRuntime("run-1")).thenReturn(Optional.of(context(AgentRunStatus.RUNNING)));
        AgentToolApplicationService service = service(65_536);

        AgentToolResult result = service.execute(command("project.get_summary", Map.of()));

        assertThat(result.toolCallId()).isEqualTo("call-1");
        assertThat(result.resultId()).isEqualTo("request-1:call-1");
        assertThat(result.data()).containsEntry("projectId", 22L);
    }

    @Test
    void rejectsMissingInactiveMismatchedAndUnknownRunsBeforeCapabilityExecution() {
        AgentToolApplicationService service = service(65_536);
        when(contextQuery.findByRunIdForRuntime("run-1")).thenReturn(Optional.empty());
        assertKind(service, command("project.get_summary", Map.of()), AgentToolErrorKind.RUN_NOT_FOUND);

        when(contextQuery.findByRunIdForRuntime("run-1"))
                .thenReturn(Optional.of(context(AgentRunStatus.SUCCEEDED)));
        assertKind(service, command("project.get_summary", Map.of()), AgentToolErrorKind.RUN_NOT_ACTIVE);

        when(contextQuery.findByRunIdForRuntime("run-1"))
                .thenReturn(Optional.of(new AgentRunExecutionContext(
                        "run-1", "other", 11, 22, 33, AgentRunStatus.RUNNING, null, null, null)));
        assertKind(service, command("project.get_summary", Map.of()), AgentToolErrorKind.PROTOCOL);

        when(contextQuery.findByRunIdForRuntime("run-1")).thenReturn(Optional.of(context(AgentRunStatus.RUNNING)));
        assertKind(service, command("java.bean.name", Map.of()), AgentToolErrorKind.UNKNOWN_TOOL);
    }

    @Test
    void enforcesResultSizeWithoutDependingOnProtobuf() {
        when(contextQuery.findByRunIdForRuntime("run-1")).thenReturn(Optional.of(context(AgentRunStatus.RUNNING)));

        assertKind(service(8), command("project.get_summary", Map.of()),
                AgentToolErrorKind.RESULT_TOO_LARGE);
    }

    private AgentToolApplicationService service(int maxBytes) {
        return new AgentToolApplicationService(
                contextQuery, List.of(handler), new AgentToolResultSizePolicy(maxBytes));
    }

    private AgentToolCommand command(String name, Map<String, Object> arguments) {
        return new AgentToolCommand("request-1", "run-1", "call-1", name, arguments);
    }

    private AgentRunExecutionContext context(AgentRunStatus status) {
        return new AgentRunExecutionContext("run-1", "request-1", 11, 22, 33, status, null, null, null);
    }

    private void assertKind(AgentToolApplicationService service, AgentToolCommand command,
                            AgentToolErrorKind kind) {
        assertThatThrownBy(() -> service.execute(command))
                .isInstanceOfSatisfying(AgentToolException.class,
                        exception -> assertThat(exception.kind()).isEqualTo(kind));
    }
}
