package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectActivityMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ProjectActivityServiceTest {
    @Test
    void explicitActorTimelineReauthorizesBeforeScopedQuery() {
        ProjectMapper projectMapper = mock(ProjectMapper.class);
        ProjectActivityMapper activityMapper = mock(ProjectActivityMapper.class);
        ProjectAuthorizationService authorization = mock(ProjectAuthorizationService.class);
        when(projectMapper.countProjectScopeInActiveWorkspace(10, 20)).thenReturn(1);
        when(activityMapper.findTimeline(10, 20, 0, 5)).thenReturn(List.of());
        ProjectActivityService service = new ProjectActivityService(
                projectMapper, activityMapper, authorization, mock(CurrentUserProvider.class));

        var result = service.queryTimelineForActor(30, 10, 20, 1, 5);

        assertThat(result.items()).isEmpty();
        verify(authorization).requirePermission(
                30, 10, 20, ProjectPermission.PROJECT_ACTIVITY_READ);
    }
}
