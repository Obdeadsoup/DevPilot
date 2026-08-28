package com.obdeadsoup.devpilot.task.application;

import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.application.port.ProjectTaskContext;
import com.obdeadsoup.devpilot.project.application.port.ProjectTaskContextQuery;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskMapper;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskStatusHistoryMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TaskQueryServiceTest {
    @Test
    void explicitActorOpenListReauthorizesAndUsesBoundedOpenSql() {
        TaskMapper mapper = mock(TaskMapper.class);
        TaskAuthorizationService authorization = mock(TaskAuthorizationService.class);
        ProjectTaskContextQuery projectQuery = mock(ProjectTaskContextQuery.class);
        when(projectQuery.findByScope(10, 20))
                .thenReturn(Optional.of(new ProjectTaskContext("DP", false, true)));
        TaskEntity task = new TaskEntity();
        task.setId(1L);
        task.setTitle("Open task");
        task.setStatus("TODO");
        task.setPriority("HIGH");
        task.setReporterUserId(30);
        when(mapper.findOpenByScope(10, 20, 5)).thenReturn(List.of(task));
        TaskQueryService service = new TaskQueryService(
                mapper, mock(TaskStatusHistoryMapper.class), projectQuery, authorization,
                mock(CurrentUserProvider.class));

        var result = service.listOpenForActor(30, 10, 20, 5);

        assertThat(result).singleElement().satisfies(item -> {
            assertThat(item.displayKey()).isEqualTo("DP-1");
            assertThat(item.description()).isNull();
        });
        verify(authorization).requirePermission(30, 10, 20, ProjectPermission.TASK_READ);
        verify(mapper).findOpenByScope(10, 20, 5);
    }

    @Test
    void explicitActorOpenListRejectsUnboundedLimitBeforeSql() {
        TaskQueryService service = new TaskQueryService(
                mock(TaskMapper.class), mock(TaskStatusHistoryMapper.class),
                mock(ProjectTaskContextQuery.class), mock(TaskAuthorizationService.class),
                mock(CurrentUserProvider.class));

        assertThatThrownBy(() -> service.listOpenForActor(30, 10, 20, 21))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
