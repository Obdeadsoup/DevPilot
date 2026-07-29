package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.identity.application.WorkspaceAuthorizationService;
import com.obdeadsoup.devpilot.identity.domain.WorkspaceRole;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.project.domain.ProjectMember;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.domain.ProjectRole;
import com.obdeadsoup.devpilot.project.error.ProjectErrorCode;
import com.obdeadsoup.devpilot.project.persistence.entity.ProjectMemberEntity;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMemberMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class ProjectMemberService {

    private final CurrentUserProvider currentUserProvider;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final ProjectMemberMapper projectMemberMapper;

    public ProjectMemberService(
            CurrentUserProvider currentUserProvider,
            ProjectAuthorizationService projectAuthorizationService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            ProjectMemberMapper projectMemberMapper
    ) {
        this.currentUserProvider = currentUserProvider;
        this.projectAuthorizationService = projectAuthorizationService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.projectMemberMapper = projectMemberMapper;
    }

    @Transactional
    public void addMember(long workspaceId, long projectId, long userId, ProjectRole role) {
        long actorUserId = requireManagePermission(workspaceId, projectId);
        requireActiveWorkspaceMember(userId, workspaceId);
        requireRole(role);
        requireRoleManagementPolicy(actorUserId, workspaceId, null, role);
        try {
            projectMemberMapper.insertActive(
                    workspaceId, projectId, userId, role.name(), actorUserId
            );
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ProjectErrorCode.PROJECT_MEMBERSHIP_CONFLICT);
        }
    }

    @Transactional
    public void changeRole(
            long workspaceId,
            long projectId,
            long userId,
            ProjectRole role,
            long expectedVersion
    ) {
        long actorUserId = requireManagePermission(workspaceId, projectId);
        requireActiveWorkspaceMember(userId, workspaceId);
        requireRole(role);
        ProjectMemberEntity member = requireMember(workspaceId, projectId, userId);
        requireRoleManagementPolicy(
                actorUserId,
                workspaceId,
                ProjectRole.valueOf(member.role()),
                role
        );
        if (projectMemberMapper.changeRole(
                workspaceId, projectId, userId, role.name(), expectedVersion
        ) != 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_MEMBERSHIP_VERSION_CONFLICT);
        }
    }

    @Transactional
    public void removeMember(
            long workspaceId,
            long projectId,
            long userId,
            long expectedVersion
    ) {
        long actorUserId = requireManagePermission(workspaceId, projectId);
        ProjectMemberEntity member = requireMember(workspaceId, projectId, userId);
        requireRoleManagementPolicy(
                actorUserId,
                workspaceId,
                ProjectRole.valueOf(member.role()),
                null
        );
        if (projectMemberMapper.remove(
                workspaceId, projectId, userId, expectedVersion
        ) != 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_MEMBERSHIP_VERSION_CONFLICT);
        }
    }

    @Transactional(readOnly = true)
    public List<ProjectMember> listMembers(long workspaceId, long projectId) {
        long actorUserId = currentUserProvider.requireUserId();
        projectAuthorizationService.requirePermission(
                actorUserId,
                workspaceId,
                projectId,
                ProjectPermission.PROJECT_MEMBER_LIST
        );
        return projectMemberMapper.findActiveByProjectScope(workspaceId, projectId)
                .stream()
                .map(member -> new ProjectMember(
                        member.id(),
                        member.workspaceId(),
                        member.projectId(),
                        member.userId(),
                        ProjectRole.valueOf(member.role()),
                        member.version()
                ))
                .toList();
    }

    private long requireManagePermission(long workspaceId, long projectId) {
        long actorUserId = currentUserProvider.requireUserId();
        projectAuthorizationService.requirePermission(
                actorUserId,
                workspaceId,
                projectId,
                ProjectPermission.PROJECT_MEMBER_MANAGE
        );
        return actorUserId;
    }

    private void requireActiveWorkspaceMember(long userId, long workspaceId) {
        if (!workspaceAuthorizationService.isActiveMember(userId, workspaceId)) {
            throw new BusinessException(ProjectErrorCode.USER_NOT_WORKSPACE_MEMBER);
        }
    }

    private void requireRole(ProjectRole role) {
        if (role == null) {
            throw new BusinessException(ProjectErrorCode.INVALID_PROJECT_ROLE);
        }
    }

    private ProjectMemberEntity requireMember(long workspaceId, long projectId, long userId) {
        return projectMemberMapper.findByScopeAndUser(workspaceId, projectId, userId)
                .filter(member -> "ACTIVE".equals(member.status()))
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_MEMBERSHIP_CONFLICT));
    }

    private void requireRoleManagementPolicy(
            long actorUserId,
            long workspaceId,
            ProjectRole currentRole,
            ProjectRole desiredRole
    ) {
        boolean workspaceAdministrator = workspaceAuthorizationService
                .getEffectiveRole(actorUserId, workspaceId)
                .map(role -> role == WorkspaceRole.OWNER || role == WorkspaceRole.ADMIN)
                .orElse(false);
        if (!workspaceAdministrator
                && (currentRole == ProjectRole.PROJECT_ADMIN || desiredRole == ProjectRole.PROJECT_ADMIN)) {
            throw new BusinessException(IdentityErrorCode.ACCESS_DENIED);
        }
    }
}
