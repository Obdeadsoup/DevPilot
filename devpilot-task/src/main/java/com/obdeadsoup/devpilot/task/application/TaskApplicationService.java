package com.obdeadsoup.devpilot.task.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.application.port.ProjectTaskContext;
import com.obdeadsoup.devpilot.project.application.port.ProjectTaskContextQuery;
import com.obdeadsoup.devpilot.task.api.dto.TaskResponse;
import com.obdeadsoup.devpilot.task.application.command.CreateTaskCommand;
import com.obdeadsoup.devpilot.task.application.command.UpdateTaskCommand;
import com.obdeadsoup.devpilot.task.application.outbox.TaskOutboxEventFactory;
import com.obdeadsoup.devpilot.task.domain.TaskPriority;
import com.obdeadsoup.devpilot.task.domain.TaskStatus;
import com.obdeadsoup.devpilot.task.error.TaskErrorCode;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;

/**
 * 本地 Task 的创建、资料更新和分配编排。Task 是团队计划的本地权威事实，不等于 GitHub Issue；
 * 外部 Issue/PR 只能由独立关联动作提供上下文，不能替代本地负责人、权限或状态机。
 */
@Service
public class TaskApplicationService {
    private final TaskMapper taskMapper;
    private final ProjectTaskContextQuery projectTaskContextQuery;
    private final TaskAuthorizationService authorizationService;
    private final TaskPersistenceService persistenceService;
    private final CurrentUserProvider currentUserProvider;
    private final Clock clock;
    private final TaskOutboxEventFactory outboxEvents;

    public TaskApplicationService(TaskMapper taskMapper, ProjectTaskContextQuery projectTaskContextQuery,
                                  TaskAuthorizationService authorizationService, TaskPersistenceService persistenceService,
                                  CurrentUserProvider currentUserProvider, Clock taskClock,
                                  TaskOutboxEventFactory outboxEvents) {
        this.taskMapper = taskMapper; this.projectTaskContextQuery = projectTaskContextQuery; this.authorizationService = authorizationService;
        this.persistenceService = persistenceService; this.currentUserProvider = currentUserProvider; this.clock = taskClock;
        this.outboxEvents = outboxEvents;
    }

    /** 创建 BACKLOG Task；初始 version 与 CREATED history 均为 0，Task/History/Activity 在一个事务提交。 */
    @Transactional
    public TaskResponse createTask(long workspaceId, long projectId, CreateTaskCommand command) {
        long actor = currentUserProvider.requireUserId();
        String projectKey = requireWritableProject(workspaceId, projectId);
        authorizationService.requirePermission(actor, workspaceId, projectId, ProjectPermission.TASK_CREATE);
        if (command.assigneeUserId() != null) authorizationService.requireEligibleAssignee(command.assigneeUserId(), workspaceId, projectId);
        LocalDateTime now = LocalDateTime.now(clock);
        TaskEntity task = preparedTask(workspaceId, projectId, actor, command.title(), command.description(), command.priority(),
                command.assigneeUserId(), command.dueAt(), now);
        return TaskResponse.from(persistenceService.create(task, actor, now), projectKey, true);
    }

    /** 只更新资料字段；expectedVersion 是客户端读到的版本，更新 0 行绝不按成功处理。 */
    @Transactional
    public TaskResponse updateTaskProfile(long workspaceId, long projectId, long taskId, UpdateTaskCommand command) {
        long actor = currentUserProvider.requireUserId();
        String projectKey = requireWritableProject(workspaceId, projectId);
        TaskEntity task = requireTask(workspaceId, projectId, taskId);
        authorizationService.requireProfileUpdate(actor, task);
        validateInput(command.title(), command.description(), command.dueAt());
        if (taskMapper.updateProfile(workspaceId, projectId, taskId, normalizeTitle(command.title()), normalizeDescription(command.description()),
                (command.priority() == null ? TaskPriority.MEDIUM : command.priority()).name(), command.dueAt(), command.expectedVersion()) != 1) {
            throw writeConflict(workspaceId, projectId, taskId, command.expectedVersion());
        }
        persistenceService.recordProfileUpdate(task, command.expectedVersion() + 1, LocalDateTime.now(clock));
        return TaskResponse.from(requireTask(workspaceId, projectId, taskId), projectKey, true);
    }

    @Transactional
    public TaskResponse assignTask(long workspaceId, long projectId, long taskId, long assigneeUserId, long expectedVersion) {
        long actor = currentUserProvider.requireUserId(); String projectKey = requireWritableProject(workspaceId, projectId);
        TaskEntity task = requireTask(workspaceId, projectId, taskId); authorizationService.requireAssignment(actor, task);
        authorizationService.requireEligibleAssignee(assigneeUserId, workspaceId, projectId);
        if (Long.valueOf(assigneeUserId).equals(task.getAssigneeUserId())) return TaskResponse.from(task, projectKey, true);
        if (taskMapper.updateAssignee(workspaceId, projectId, taskId, assigneeUserId, expectedVersion) != 1) throw writeConflict(workspaceId, projectId, taskId, expectedVersion);
        LocalDateTime occurredAt = LocalDateTime.now(clock);
        persistenceService.recordAssignment(task, expectedVersion + 1, true, occurredAt);
        outboxEvents.publishAssigned(task, projectKey, expectedVersion + 1, actor, assigneeUserId, occurredAt);
        return TaskResponse.from(requireTask(workspaceId, projectId, taskId), projectKey, true);
    }

    @Transactional
    public TaskResponse unassignTask(long workspaceId, long projectId, long taskId, long expectedVersion) {
        long actor = currentUserProvider.requireUserId(); String projectKey = requireWritableProject(workspaceId, projectId);
        TaskEntity task = requireTask(workspaceId, projectId, taskId); authorizationService.requireAssignment(actor, task);
        if (task.getAssigneeUserId() == null) return TaskResponse.from(task, projectKey, true);
        long previousAssignee = task.getAssigneeUserId();
        if (taskMapper.updateAssignee(workspaceId, projectId, taskId, null, expectedVersion) != 1) throw writeConflict(workspaceId, projectId, taskId, expectedVersion);
        LocalDateTime occurredAt = LocalDateTime.now(clock);
        persistenceService.recordAssignment(task, expectedVersion + 1, false, occurredAt);
        outboxEvents.publishUnassigned(task, projectKey, expectedVersion + 1, actor, previousAssignee, occurredAt);
        return TaskResponse.from(requireTask(workspaceId, projectId, taskId), projectKey, true);
    }

    TaskEntity preparedTask(long workspaceId, long projectId, long reporterId, String title, String description,
                            TaskPriority priority, Long assigneeUserId, LocalDateTime dueAt, LocalDateTime now) {
        validateInput(title, description, dueAt);
        TaskEntity task = new TaskEntity(); task.setWorkspaceId(workspaceId); task.setProjectId(projectId);
        task.setTitle(normalizeTitle(title)); task.setDescription(normalizeDescription(description));
        task.setStatus(TaskStatus.BACKLOG.name()); task.setPriority((priority == null ? TaskPriority.MEDIUM : priority).name());
        task.setReporterUserId(reporterId); task.setAssigneeUserId(assigneeUserId); task.setDueAt(dueAt); task.setVersion(0); task.setDeleted(false);
        return task;
    }

    String requireWritableProject(long workspaceId, long projectId) {
        ProjectTaskContext project = projectTaskContextQuery.findByScope(workspaceId, projectId)
                .orElseThrow(() -> new BusinessException(TaskErrorCode.TASK_NOT_FOUND));
        if (project.archived()) throw new BusinessException(TaskErrorCode.TASK_PROJECT_ARCHIVED);
        if (!project.activeScope()) throw new BusinessException(TaskErrorCode.TASK_NOT_FOUND);
        return project.projectKey();
    }

    TaskEntity requireTask(long workspaceId, long projectId, long taskId) {
        return taskMapper.findByScope(workspaceId, projectId, taskId)
                .orElseThrow(() -> new BusinessException(TaskErrorCode.TASK_NOT_FOUND));
    }

    BusinessException writeConflict(long workspaceId, long projectId, long taskId, long expectedVersion) {
        TaskEntity current = taskMapper.findByScope(workspaceId, projectId, taskId)
                .orElseThrow(() -> new BusinessException(TaskErrorCode.TASK_NOT_FOUND));
        return current.getVersion() != expectedVersion
                ? new BusinessException(TaskErrorCode.TASK_VERSION_CONFLICT)
                : new BusinessException(TaskErrorCode.TASK_INVALID_TRANSITION);
    }

    private void validateInput(String title, String description, LocalDateTime dueAt) {
        if (title == null || normalizeTitle(title).isEmpty() || normalizeTitle(title).length() > 255) throw new BusinessException(TaskErrorCode.INVALID_TASK_TITLE);
        if (description != null && description.length() > 10000) throw new BusinessException(TaskErrorCode.INVALID_TASK_TITLE);
        if (dueAt != null && dueAt.isBefore(LocalDateTime.now(clock))) throw new BusinessException(TaskErrorCode.INVALID_TASK_DUE_AT);
    }
    private String normalizeTitle(String title) { return title == null ? "" : title.trim().replaceAll("\\s+", " "); }
    private String normalizeDescription(String description) { return description == null ? null : description.trim(); }
}
