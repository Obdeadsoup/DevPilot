package com.obdeadsoup.devpilot.agent.application.tool;

import com.obdeadsoup.devpilot.agent.application.AgentRunExecutionContext;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.project.api.dto.ProjectActivityPageResponse;
import com.obdeadsoup.devpilot.project.api.dto.ProjectActivityResponse;
import com.obdeadsoup.devpilot.project.api.dto.ProjectResponse;
import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import com.obdeadsoup.devpilot.project.application.ProjectService;
import com.obdeadsoup.devpilot.project.domain.ProjectStatus;
import com.obdeadsoup.devpilot.project.domain.ProjectVisibility;
import com.obdeadsoup.devpilot.task.api.dto.TaskResponse;
import com.obdeadsoup.devpilot.task.application.TaskQueryService;
import com.obdeadsoup.devpilot.task.domain.TaskPriority;
import com.obdeadsoup.devpilot.task.domain.TaskStatus;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentReadToolHandlersTest {
    private static final AgentRunExecutionContext CONTEXT = new AgentRunExecutionContext(
            "run", "request", 10, 20, 30, AgentRunStatus.RUNNING, "octo/demo", "agent", "a".repeat(40));

    @Test
    void projectSummaryUsesExplicitActorAndBoundsDescription() {
        ProjectService projectService = mock(ProjectService.class);
        when(projectService.getProjectForActor(30, 10, 20)).thenReturn(new ProjectResponse(
                20, 10, "DevPilot", "DP", "x".repeat(3_000), ProjectStatus.ACTIVE,
                ProjectVisibility.PRIVATE, 0, LocalDateTime.now(), LocalDateTime.now()));

        Map<String, Object> result = new ProjectSummaryToolHandler(projectService)
                .execute(CONTEXT, Map.of());

        assertThat((String) result.get("description")).hasSize(2_000);
        assertThat(result).containsEntry("external_untrusted_content", true);
        verify(projectService).getProjectForActor(30, 10, 20);
    }

    @Test
    void taskToolUsesExplicitActorRejectsBadLimitAndOmitsDescription() {
        TaskQueryService taskQueryService = mock(TaskQueryService.class);
        when(taskQueryService.listOpenForActor(30, 10, 20, 5)).thenReturn(List.of(new TaskResponse(
                1, "DP-1", "Todo", null, TaskStatus.TODO, TaskPriority.HIGH,
                30, 31L, null, null, null, null, null, 0)));
        ListOpenTasksToolHandler handler = new ListOpenTasksToolHandler(taskQueryService);

        Map<String, Object> result = handler.execute(CONTEXT, Map.of("limit", 5));

        assertThat(result).containsEntry("count", 1).containsEntry("external_untrusted_content", true);
        assertThat(result.toString()).doesNotContain("description");
        verify(taskQueryService).listOpenForActor(30, 10, 20, 5);
        assertThatThrownBy(() -> handler.execute(CONTEXT, Map.of("limit", 21)))
                .isInstanceOf(AgentToolException.class);
    }

    @Test
    void activityToolUsesExplicitActorAndMarksEveryTextItemUntrusted() {
        ProjectActivityService activityService = mock(ProjectActivityService.class);
        ProjectActivityResponse activity = new ProjectActivityResponse(
                1, 10, 20, null, null, "TASK", "TASK_CREATED", "source", null,
                null, null, null, null, null, null, "title", "summary", null,
                LocalDateTime.of(2026, 8, 27, 12, 0));
        when(activityService.queryTimelineForActor(30, 10, 20, 1, 10))
                .thenReturn(new ProjectActivityPageResponse(List.of(activity), 1, 10, 1, 1));

        Map<String, Object> result = new RecentProjectActivityToolHandler(activityService)
                .execute(CONTEXT, Map.of());

        assertThat(result.toString()).contains("externalUntrustedContent=true");
        verify(activityService).queryTimelineForActor(30, 10, 20, 1, 10);
    }
}
