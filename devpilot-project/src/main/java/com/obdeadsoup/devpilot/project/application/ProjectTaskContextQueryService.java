package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.project.application.port.ProjectTaskContext;
import com.obdeadsoup.devpilot.project.application.port.ProjectTaskContextQuery;
import com.obdeadsoup.devpilot.project.domain.ProjectStatus;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 将 Task 需要的 Project Key、归档状态和活动 Scope 收敛为只读 DTO，隔离 Project Mapper/Entity。
 */
@Service
public class ProjectTaskContextQueryService implements ProjectTaskContextQuery {

    private final ProjectMapper projectMapper;

    public ProjectTaskContextQueryService(ProjectMapper projectMapper) {
        this.projectMapper = projectMapper;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<ProjectTaskContext> findByScope(long workspaceId, long projectId) {
        return projectMapper.findByScope(workspaceId, projectId)
                .map(project -> new ProjectTaskContext(
                        project.projectKey(),
                        ProjectStatus.ARCHIVED.name().equals(project.status()),
                        projectMapper.countActiveProjectScope(workspaceId, projectId) == 1
                ));
    }
}
