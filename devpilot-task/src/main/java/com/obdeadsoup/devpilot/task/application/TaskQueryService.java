package com.obdeadsoup.devpilot.task.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import com.obdeadsoup.devpilot.project.application.port.ProjectTaskContextQuery;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.task.api.dto.TaskDetailResponse;
import com.obdeadsoup.devpilot.task.api.dto.TaskResponse;
import com.obdeadsoup.devpilot.task.api.dto.TaskStatusHistoryResponse;
import com.obdeadsoup.devpilot.task.domain.TaskPriority;
import com.obdeadsoup.devpilot.task.domain.TaskStatus;
import com.obdeadsoup.devpilot.task.error.TaskErrorCode;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskMapper;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskStatusHistoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** Task 查询在授权后把过滤条件直接下推到 scoped SQL；列表不返回 description 或完整 history。 */
@Service
public class TaskQueryService {
    private final TaskMapper taskMapper;
    private final TaskStatusHistoryMapper historyMapper;
    private final ProjectTaskContextQuery projectTaskContextQuery;
    private final TaskAuthorizationService authorizationService;
    private final CurrentUserProvider currentUserProvider;

    public TaskQueryService(TaskMapper taskMapper,
                            TaskStatusHistoryMapper historyMapper,
                            ProjectTaskContextQuery projectTaskContextQuery,
                            TaskAuthorizationService authorizationService,
                            CurrentUserProvider currentUserProvider) {
        this.taskMapper = taskMapper;
        this.historyMapper = historyMapper;
        this.projectTaskContextQuery = projectTaskContextQuery;
        this.authorizationService = authorizationService;
        this.currentUserProvider = currentUserProvider;
    }

    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> list(long workspaceId, long projectId, int page, int size,
                                           TaskStatus status, TaskPriority priority, Long assignee,
                                           Long reporter, LocalDateTime dueBefore) {
        return listForActor(currentUserProvider.requireUserId(), workspaceId, projectId, page, size,
                status, priority, assignee, reporter, dueBefore);
    }

    /** 显式 actor 查询仍经过 TaskAuthorizationService，供 run-bound delegation 安全复用。 */
    @Transactional(readOnly = true)
    public PageResponse<TaskResponse> listForActor(long actorUserId, long workspaceId, long projectId,
                                                   int page, int size, TaskStatus status,
                                                   TaskPriority priority, Long assignee, Long reporter,
                                                   LocalDateTime dueBefore) {
        authorizationService.requirePermission(
                actorUserId, workspaceId, projectId, ProjectPermission.TASK_READ);
        String projectKey = requireProjectKey(workspaceId, projectId);
        String statusName = status == null ? null : status.name();
        String priorityName = priority == null ? null : priority.name();
        long total = taskMapper.countPage(workspaceId, projectId, statusName, priorityName,
                assignee, reporter, dueBefore);
        List<TaskResponse> items = taskMapper.findPage(workspaceId, projectId, statusName,
                        priorityName, assignee, reporter, dueBefore, (long) (page - 1) * size, size)
                .stream().map(task -> TaskResponse.from(task, projectKey, false)).toList();
        return new PageResponse<>(page, size, total, items);
    }

    /** 只返回最多 20 条非终态 Task，description/history 永不进入 Agent Tool result。 */
    @Transactional(readOnly = true)
    public List<TaskResponse> listOpenForActor(long actorUserId, long workspaceId,
                                               long projectId, int limit) {
        if (limit < 1 || limit > 20) {
            throw new IllegalArgumentException("limit must be between 1 and 20");
        }
        authorizationService.requirePermission(
                actorUserId, workspaceId, projectId, ProjectPermission.TASK_READ);
        String projectKey = requireProjectKey(workspaceId, projectId);
        return taskMapper.findOpenByScope(workspaceId, projectId, limit).stream()
                .map(task -> TaskResponse.from(task, projectKey, false))
                .toList();
    }

    @Transactional(readOnly = true)
    public TaskDetailResponse get(long workspaceId, long projectId, long taskId) {
        authorizationService.requirePermission(currentUserProvider.requireUserId(), workspaceId,
                projectId, ProjectPermission.TASK_READ);
        String projectKey = requireProjectKey(workspaceId, projectId);
        TaskEntity task = taskMapper.findByScope(workspaceId, projectId, taskId)
                .orElseThrow(() -> new BusinessException(TaskErrorCode.TASK_NOT_FOUND));
        return new TaskDetailResponse(
                TaskResponse.from(task, projectKey, true),
                historyMapper.findByTaskScope(workspaceId, projectId, taskId).stream()
                        .map(TaskStatusHistoryResponse::from).toList());
    }

    private String requireProjectKey(long workspaceId, long projectId) {
        return projectTaskContextQuery.findByScope(workspaceId, projectId)
                .map(context -> context.projectKey())
                .orElseThrow(() -> new BusinessException(TaskErrorCode.TASK_NOT_FOUND));
    }
}
