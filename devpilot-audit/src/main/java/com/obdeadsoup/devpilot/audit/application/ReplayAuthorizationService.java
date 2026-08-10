package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.error.AuditErrorCode;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.application.WorkspaceAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.domain.ProjectRole;
import com.obdeadsoup.devpilot.project.domain.WorkspaceRole;
import org.springframework.stereotype.Service;

/**
 * DEAD 运维权限入口。管理员身份也不能绕过 Workspace/Project scope；Outbox 仅 Project Admin，Sync 沿用 REPOSITORY_UPDATE。
 */
@Service
public class ReplayAuthorizationService {
    private final ProjectAuthorizationService projects;
    private final WorkspaceAuthorizationService workspaces;

    public ReplayAuthorizationService(ProjectAuthorizationService projects,
                                      WorkspaceAuthorizationService workspaces) {
        this.projects = projects;
        this.workspaces = workspaces;
    }

    public void requireOutboxAdministration(long userId, long workspaceId, long projectId) {
        boolean allowed = projects.getEffectiveProjectAccess(userId, workspaceId, projectId)
                .map(access -> access.effectiveRole() == ProjectRole.PROJECT_ADMIN).orElse(false);
        if (!allowed) deny();
    }

    public void requireSyncReplay(long userId, long workspaceId, long projectId) {
        if (!projects.hasPermission(userId, workspaceId, projectId, ProjectPermission.REPOSITORY_UPDATE)) deny();
    }

    public void requireAuditRead(long userId, long workspaceId, Long projectId) {
        var role = workspaces.getEffectiveRole(userId, workspaceId);
        if (role.filter(value -> value == WorkspaceRole.OWNER || value == WorkspaceRole.ADMIN).isPresent()) return;
        if (projectId != null && projects.getEffectiveProjectAccess(userId, workspaceId, projectId)
                .map(access -> access.effectiveRole() == ProjectRole.PROJECT_ADMIN).orElse(false)) return;
        deny();
    }

    private void deny() { throw new BusinessException(AuditErrorCode.AUDIT_ACCESS_DENIED); }
}
