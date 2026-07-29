package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.WorkspaceAuthorizationService;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.domain.WorkspaceRole;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.project.domain.ProjectAccess;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.domain.ProjectRole;
import com.obdeadsoup.devpilot.project.persistence.entity.ProjectEntity;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMemberMapper;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service("projectAuthorization")
public class ProjectAuthorizationService {

    private static final String ARCHIVED = "ARCHIVED";
    private static final String INTERNAL = "INTERNAL";
    private static final String ACTIVE = "ACTIVE";

    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;

    public ProjectAuthorizationService(
            ProjectMapper projectMapper,
            ProjectMemberMapper projectMemberMapper,
            WorkspaceAuthorizationService workspaceAuthorizationService
    ) {
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
    }

    @Transactional(readOnly = true)
    public Optional<ProjectAccess> getEffectiveProjectAccess(
            long userId,
            long workspaceId,
            long projectId
    ) {
        Optional<ProjectEntity> projectOptional = projectMapper.findByScope(workspaceId, projectId);
        Optional<WorkspaceRole> workspaceRoleOptional =
                workspaceAuthorizationService.getEffectiveRole(userId, workspaceId);
        if (projectOptional.isEmpty() || workspaceRoleOptional.isEmpty()) {
            return Optional.empty();
        }

        ProjectEntity project = projectOptional.get();
        WorkspaceRole workspaceRole = workspaceRoleOptional.get();
        ProjectAccess access;
        if (workspaceRole == WorkspaceRole.OWNER || workspaceRole == WorkspaceRole.ADMIN) {
            access = access(
                    ProjectRole.PROJECT_ADMIN,
                    ProjectAccess.Source.WORKSPACE_ADMINISTRATION,
                    project
            );
        } else {
            access = projectMemberMapper.findByScopeAndUser(workspaceId, projectId, userId)
                    .filter(member -> ACTIVE.equals(member.status()))
                    .map(member -> access(
                            ProjectRole.valueOf(member.role()),
                            ProjectAccess.Source.PROJECT_MEMBERSHIP,
                            project
                    ))
                    .orElseGet(() -> INTERNAL.equals(project.visibility())
                            ? access(
                                    ProjectRole.VIEWER,
                                    ProjectAccess.Source.INTERNAL_WORKSPACE_MEMBERSHIP,
                                    project
                            )
                            : null);
        }
        return Optional.ofNullable(access);
    }

    @Transactional(readOnly = true)
    public boolean hasPermission(
            long userId,
            long workspaceId,
            long projectId,
            ProjectPermission permission
    ) {
        return getEffectiveProjectAccess(userId, workspaceId, projectId)
                .map(access -> access.hasPermission(permission))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public boolean hasPermission(
            Authentication authentication,
            long workspaceId,
            long projectId,
            String permissionName
    ) {
        if (authentication == null
                || !authentication.isAuthenticated()
                || !(authentication.getPrincipal() instanceof DevPilotUserPrincipal principal)) {
            return false;
        }
        try {
            return hasPermission(
                    principal.id(),
                    workspaceId,
                    projectId,
                    ProjectPermission.valueOf(permissionName)
            );
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    @Transactional(readOnly = true)
    public void requirePermission(
            long userId,
            long workspaceId,
            long projectId,
            ProjectPermission permission
    ) {
        if (!hasPermission(userId, workspaceId, projectId, permission)) {
            throw new BusinessException(IdentityErrorCode.ACCESS_DENIED);
        }
    }

    private ProjectAccess access(
            ProjectRole role,
            ProjectAccess.Source source,
            ProjectEntity project
    ) {
        Set<ProjectPermission> permissions = role.permissions();
        if (ARCHIVED.equals(project.status())) {
            permissions = permissions.stream()
                    .filter(ProjectPermission::isReadOnly)
                    .collect(Collectors.toUnmodifiableSet());
        }
        return new ProjectAccess(role, source, permissions);
    }
}
