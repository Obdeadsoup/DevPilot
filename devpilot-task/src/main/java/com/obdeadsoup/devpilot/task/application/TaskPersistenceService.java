package com.obdeadsoup.devpilot.task.application;

import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import com.obdeadsoup.devpilot.project.application.command.RecordTaskProjectActivityCommand;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;
import com.obdeadsoup.devpilot.task.domain.TaskAction;
import com.obdeadsoup.devpilot.task.domain.TaskTransition;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskMapper;
import com.obdeadsoup.devpilot.task.persistence.mapper.TaskStatusHistoryMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Task 写入、状态历史和本地 Project Activity 的事务边界。状态更新与 History 必须同时提交，
 * 否则 version 所表达的事实与可审计状态链会分叉；Activity 的确定性来源键承担最后的并发去重。
 */
@Service
public class TaskPersistenceService {
    private final TaskMapper taskMapper;
    private final TaskStatusHistoryMapper historyMapper;
    private final ProjectActivityService activityService;

    public TaskPersistenceService(TaskMapper taskMapper, TaskStatusHistoryMapper historyMapper,
                                  ProjectActivityService activityService) {
        this.taskMapper = taskMapper; this.historyMapper = historyMapper; this.activityService = activityService;
    }

    @Transactional
    public TaskEntity create(TaskEntity task, long actorUserId, LocalDateTime occurredAt) {
        taskMapper.insert(task);
        historyMapper.insert(task.getWorkspaceId(), task.getProjectId(), task.getId(), null, task.getStatus(),
                TaskAction.CREATED.name(), actorUserId, null, 0, occurredAt);
        record(task, 0, ProjectActivityType.TASK_CREATED, "创建 Task：" + task.getTitle(), null, occurredAt);
        return taskMapper.findByScope(task.getWorkspaceId(), task.getProjectId(), task.getId()).orElseThrow();
    }

    @Transactional
    public void recordProfileUpdate(TaskEntity task, long newVersion, LocalDateTime occurredAt) {
        record(task, newVersion, ProjectActivityType.TASK_UPDATED, "更新 Task：" + task.getTitle(), null, occurredAt);
    }

    @Transactional
    public void recordAssignment(TaskEntity task, long newVersion, boolean assigned, LocalDateTime occurredAt) {
        record(task, newVersion, assigned ? ProjectActivityType.TASK_ASSIGNED : ProjectActivityType.TASK_UNASSIGNED,
                (assigned ? "分配" : "取消分配") + " Task：" + task.getTitle(), null, occurredAt);
    }

    @Transactional
    public void recordTransition(TaskEntity task, TaskTransition transition, long actorUserId, String reason,
                                 long newVersion, LocalDateTime occurredAt) {
        historyMapper.insert(task.getWorkspaceId(), task.getProjectId(), task.getId(), transition.from().name(),
                transition.to().name(), transition.action().name(), actorUserId, reason, newVersion, occurredAt);
        record(task, newVersion, activityType(transition.action()), transition.action().name() + "：" + task.getTitle(),
                reason, occurredAt);
    }

    @Transactional
    public void recordLink(TaskEntity task, long newVersion, boolean removed, LocalDateTime occurredAt) {
        record(task, newVersion, removed ? ProjectActivityType.TASK_GITHUB_UNLINKED : ProjectActivityType.TASK_GITHUB_LINKED,
                (removed ? "解除 GitHub 关联：" : "关联 GitHub 资源：") + task.getTitle(), null, occurredAt);
    }

    private void record(TaskEntity task, long version, ProjectActivityType type, String title, String summary,
                        LocalDateTime occurredAt) {
        activityService.recordTaskActivity(new RecordTaskProjectActivityCommand(task.getWorkspaceId(), task.getProjectId(),
                task.getId(), version, type, title, summary, occurredAt));
    }

    private ProjectActivityType activityType(TaskAction action) {
        return switch (action) {
            case PLANNED -> ProjectActivityType.TASK_PLANNED;
            case RETURNED_TO_BACKLOG -> ProjectActivityType.TASK_RETURNED_TO_BACKLOG;
            case STARTED -> ProjectActivityType.TASK_STARTED;
            case SUBMITTED_FOR_REVIEW -> ProjectActivityType.TASK_SUBMITTED_FOR_REVIEW;
            case CHANGES_REQUESTED -> ProjectActivityType.TASK_CHANGES_REQUESTED;
            case COMPLETED -> ProjectActivityType.TASK_COMPLETED;
            case CANCELED -> ProjectActivityType.TASK_CANCELED;
            case REOPENED -> ProjectActivityType.TASK_REOPENED;
            case CREATED -> ProjectActivityType.TASK_CREATED;
        };
    }
}
