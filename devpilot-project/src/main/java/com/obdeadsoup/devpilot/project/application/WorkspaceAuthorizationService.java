package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.project.domain.WorkspaceMemberStatus;
import com.obdeadsoup.devpilot.project.domain.WorkspacePermission;
import com.obdeadsoup.devpilot.project.domain.WorkspaceRole;
import com.obdeadsoup.devpilot.project.domain.WorkspaceStatus;
import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceEntity;
import com.obdeadsoup.devpilot.project.persistence.mapper.WorkspaceMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.WorkspaceMemberMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;

@Service
public class WorkspaceAuthorizationService {

    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper workspaceMemberMapper;

    public WorkspaceAuthorizationService(
            WorkspaceMapper workspaceMapper,
            WorkspaceMemberMapper workspaceMemberMapper
    ) {
        this.workspaceMapper = workspaceMapper;
        this.workspaceMemberMapper = workspaceMemberMapper;
    }

    @Transactional(readOnly = true)
    public Optional<WorkspaceRole> getEffectiveRole(long userId, long workspaceId) {
        Optional<WorkspaceEntity> workspaceOptional = workspaceMapper.findById(workspaceId);
        if (workspaceOptional.isEmpty()
                || !WorkspaceStatus.ACTIVE.name().equals(workspaceOptional.get().status())) {
            return Optional.empty();
        }

        WorkspaceEntity workspace = workspaceOptional.get();
        if (isOwner(workspace, userId)) {
            return Optional.of(WorkspaceRole.OWNER);
        }

        return workspaceMemberMapper.findByWorkspaceAndUser(workspaceId, userId)
                .filter(member -> WorkspaceMemberStatus.ACTIVE.name().equals(member.status()))
                .map(member -> WorkspaceRole.valueOf(member.role()));
    }

    @Transactional(readOnly = true)
    public boolean hasPermission(long userId, long workspaceId, WorkspacePermission permission) {
        return getEffectiveRole(userId, workspaceId)
                .map(role -> role.hasPermission(permission))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public void requirePermission(long userId, long workspaceId, WorkspacePermission permission) {
        if (!hasPermission(userId, workspaceId, permission)) {
            throw new BusinessException(IdentityErrorCode.ACCESS_DENIED);
        }
    }

    @Transactional(readOnly = true)
    public boolean isActiveMember(long userId, long workspaceId) {
        return getEffectiveRole(userId, workspaceId).isPresent();
    }

    @Transactional(readOnly = true)
    public boolean isOwner(long userId, long workspaceId) {
        return workspaceMapper.findById(workspaceId)
                .map(workspace -> isOwner(workspace, userId))
                .orElse(false);
    }

    private boolean isOwner(WorkspaceEntity workspace, long userId) {
        return workspace.ownerUserId() != null && workspace.ownerUserId() == userId;
    }
}
