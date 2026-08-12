package com.obdeadsoup.devpilot.task.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.IdentityUserEligibilityService;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.domain.ProjectRole;
import com.obdeadsoup.devpilot.task.domain.TaskAction;
import com.obdeadsoup.devpilot.task.domain.TaskStatus;
import com.obdeadsoup.devpilot.task.error.TaskErrorCode;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在 Project RBAC 上叠加 Task 的 Reporter、Assignee、Manager 属性规则。Manager 是 Workspace
 * OWNER/ADMIN 的 PROJECT_ADMIN 等效角色或 Project PROJECT_ADMIN；客户端不能传 currentUserId。
 */
@Service
public class TaskAuthorizationService {
    private final ProjectAuthorizationService projectAuthorizationService;
    private final IdentityUserEligibilityService identityUserEligibilityService;

    public TaskAuthorizationService(ProjectAuthorizationService projectAuthorizationService,
                                    IdentityUserEligibilityService identityUserEligibilityService) {
        this.projectAuthorizationService = projectAuthorizationService;
        this.identityUserEligibilityService = identityUserEligibilityService;
    }

    @Transactional(readOnly = true)
    public void requirePermission(long userId, long workspaceId, long projectId, ProjectPermission permission) {
        if (!projectAuthorizationService.hasPermission(userId, workspaceId, projectId, permission)) {
            throw new BusinessException(TaskErrorCode.TASK_PERMISSION_DENIED);
        }
    }

    @Transactional(readOnly = true)
    public boolean isManager(long userId, long workspaceId, long projectId) {
        return projectAuthorizationService.getEffectiveProjectAccess(userId, workspaceId, projectId)
                .map(access -> access.effectiveRole() == ProjectRole.PROJECT_ADMIN)
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public void requireProfileUpdate(long userId, TaskEntity task) {
        requirePermission(userId, task.getWorkspaceId(), task.getProjectId(), ProjectPermission.TASK_UPDATE);
        if (!isRelatedOrManager(userId, task)) throw new BusinessException(TaskErrorCode.TASK_PERMISSION_DENIED);
    }

    /** 当前策略只有 Manager 能分配，避免 Reporter 在无项目管理权时把 Task 强塞给他人。 */
    @Transactional(readOnly = true)
    public void requireAssignment(long userId, TaskEntity task) {
        requirePermission(userId, task.getWorkspaceId(), task.getProjectId(), ProjectPermission.TASK_ASSIGN);
        if (!isManager(userId, task.getWorkspaceId(), task.getProjectId())) {
            throw new BusinessException(TaskErrorCode.TASK_PERMISSION_DENIED);
        }
    }

    @Transactional(readOnly = true)
    public void requireEligibleAssignee(long userId, long workspaceId, long projectId) {
        if (!identityUserEligibilityService.isActive(userId)
                || !projectAuthorizationService.hasPermission(userId, workspaceId, projectId, ProjectPermission.TASK_READ)) {
            throw new BusinessException(TaskErrorCode.TASK_ASSIGNEE_NOT_ELIGIBLE);
        }
    }

    @Transactional(readOnly = true)
    public void requireWorkflow(long userId, TaskEntity task, TaskAction action) {
        requirePermission(userId, task.getWorkspaceId(), task.getProjectId(), ProjectPermission.TASK_STATUS_CHANGE);
        boolean manager = isManager(userId, task.getWorkspaceId(), task.getProjectId());
        boolean reporter = task.getReporterUserId() == userId;
        boolean assignee = task.getAssigneeUserId() != null && task.getAssigneeUserId() == userId;
        boolean allowed = switch (action) {
            case PLANNED, RETURNED_TO_BACKLOG -> reporter || assignee || manager;
            case STARTED, SUBMITTED_FOR_REVIEW -> assignee || manager;
            case CHANGES_REQUESTED, COMPLETED, REOPENED -> manager;
            case CANCELED -> manager || (reporter && (TaskStatus.BACKLOG.name().equals(task.getStatus())
                    || TaskStatus.TODO.name().equals(task.getStatus())));
            case CREATED -> false;
        };
        if (!allowed) throw new BusinessException(TaskErrorCode.TASK_PERMISSION_DENIED);
    }

    @Transactional(readOnly = true)
    public void requireLinkUpdate(long userId, TaskEntity task) {
        requireProfileUpdate(userId, task);
        if (TaskStatus.valueOf(task.getStatus()).isTerminal()
                && !isManager(userId, task.getWorkspaceId(), task.getProjectId())) {
            throw new BusinessException(TaskErrorCode.TASK_PERMISSION_DENIED);
        }
    }

    private boolean isRelatedOrManager(long userId, TaskEntity task) {
        return task.getReporterUserId() == userId
                || (task.getAssigneeUserId() != null && task.getAssigneeUserId() == userId)
                || isManager(userId, task.getWorkspaceId(), task.getProjectId());
    }
}
