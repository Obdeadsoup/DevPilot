package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.project.api.dto.ProjectActivityPageResponse;
import com.obdeadsoup.devpilot.project.api.dto.ProjectActivityResponse;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;
import com.obdeadsoup.devpilot.project.error.ProjectErrorCode;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectActivityMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectActivityService {

    private final ProjectMapper projectMapper;
    private final ProjectActivityMapper activityMapper;

    public ProjectActivityService(ProjectMapper projectMapper, ProjectActivityMapper activityMapper) {
        this.projectMapper = projectMapper;
        this.activityMapper = activityMapper;
    }

    @Transactional
    public boolean recordGitHubActivity(RecordProjectActivityCommand command) {
        requireActiveProject(command.workspaceId(), command.projectId());
        return activityMapper.insertIfAbsent(command) == 1;
    }

    @Transactional(readOnly = true)
    public ProjectActivityPageResponse queryTimeline(long workspaceId, long projectId, int page, int size) {
        requireActiveProject(workspaceId, projectId);
        long total = activityMapper.countTimeline(workspaceId, projectId);
        long offset = (long) (page - 1) * size;
        List<ProjectActivityResponse> items = activityMapper.findTimeline(workspaceId, projectId, offset, size)
                .stream()
                .map(ProjectActivityResponse::from)
                .toList();
        long totalPages = total == 0 ? 0 : (total + size - 1) / size;
        return new ProjectActivityPageResponse(items, page, size, total, totalPages);
    }

    private void requireActiveProject(long workspaceId, long projectId) {
        if (projectMapper.countActiveProjectScope(workspaceId, projectId) != 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }
    }
}
