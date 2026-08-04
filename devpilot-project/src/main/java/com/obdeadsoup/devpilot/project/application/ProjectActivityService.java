package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.project.api.dto.ProjectActivityPageResponse;
import com.obdeadsoup.devpilot.project.api.dto.ProjectActivityResponse;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;
import com.obdeadsoup.devpilot.project.application.command.RecordTaskProjectActivityCommand;
import com.obdeadsoup.devpilot.project.domain.ProjectActivitySourceType;
import com.obdeadsoup.devpilot.project.error.ProjectErrorCode;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectActivityMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import org.springframework.security.access.prepost.PreAuthorize;
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
        requireActivityWritableProject(command.workspaceId(), command.projectId());
        return activityMapper.insertIfAbsent(command) == 1;
    }

    /**
     * 在调用方的 Task 写事务中写入本地 Activity。来源键由 taskId 与递增 version 确定，
     * 唯一索引是并发重放时最终防线，不依赖伪造 GitHub Delivery。
     */
    @Transactional
    public boolean recordTaskActivity(RecordTaskProjectActivityCommand command) {
        requireActivityWritableProject(command.workspaceId(), command.projectId());
        return activityMapper.insertIfAbsent(new RecordProjectActivityCommand(
                command.workspaceId(), command.projectId(), null, null,
                ProjectActivitySourceType.TASK, command.activityType(),
                "task:" + command.taskId() + ":v" + command.taskVersion(),
                null, null, null, null, null, null, null, command.title(), command.summary(), null,
                command.occurredAt()
        )) == 1;
    }

    @PreAuthorize(
            "@projectAuthorization.hasPermission("
                    + "authentication, #workspaceId, #projectId, 'PROJECT_ACTIVITY_READ')"
    )
    @Transactional(readOnly = true)
    public ProjectActivityPageResponse queryTimeline(long workspaceId, long projectId, int page, int size) {
        requireProjectInActiveWorkspace(workspaceId, projectId);
        long total = activityMapper.countTimeline(workspaceId, projectId);
        long offset = (long) (page - 1) * size;
        List<ProjectActivityResponse> items = activityMapper.findTimeline(workspaceId, projectId, offset, size)
                .stream()
                .map(ProjectActivityResponse::from)
                .toList();
        long totalPages = total == 0 ? 0 : (total + size - 1) / size;
        return new ProjectActivityPageResponse(items, page, size, total, totalPages);
    }

    private void requireActivityWritableProject(long workspaceId, long projectId) {
        if (projectMapper.countActiveProjectScope(workspaceId, projectId) != 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }
    }

    private void requireProjectInActiveWorkspace(long workspaceId, long projectId) {
        if (projectMapper.countProjectScopeInActiveWorkspace(workspaceId, projectId) != 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND);
        }
    }
}
