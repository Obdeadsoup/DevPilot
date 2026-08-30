package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.identity.application.UserAccountService;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.project.domain.WorkspacePermission;
import com.obdeadsoup.devpilot.project.domain.WorkspaceRole;
import com.obdeadsoup.devpilot.project.error.WorkspaceErrorCode;
import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceEntity;
import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceMemberEntity;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMemberMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.WorkspaceMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.WorkspaceMemberMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class WorkspaceMemberService {

    private final CurrentUserProvider currentUserProvider;
    private final WorkspaceAuthorizationService authorizationService;
    private final WorkspaceMapper workspaceMapper;
    private final WorkspaceMemberMapper memberMapper;
    private final UserAccountService userAccountService;
    private final ProjectMemberMapper projectMemberMapper;

    public WorkspaceMemberService(
            CurrentUserProvider currentUserProvider,
            WorkspaceAuthorizationService authorizationService,
            WorkspaceMapper workspaceMapper,
            WorkspaceMemberMapper memberMapper,
            UserAccountService userAccountService,
            ProjectMemberMapper projectMemberMapper
    ) {
        this.currentUserProvider = currentUserProvider;
        this.authorizationService = authorizationService;
        this.workspaceMapper = workspaceMapper;
        this.memberMapper = memberMapper;
        this.userAccountService = userAccountService;
        this.projectMemberMapper = projectMemberMapper;
    }

    @Transactional
    public void inviteMember(long workspaceId, long userId, WorkspaceRole role) {
        long actorUserId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(
                actorUserId, workspaceId, WorkspacePermission.WORKSPACE_MEMBER_INVITE
        );
        requireAssignableMemberRole(role);
        requireActiveUser(userId);
        requireAdminAssignmentPolicy(actorUserId, workspaceId, role);
        if (actorUserId == userId) {
            throw new BusinessException(WorkspaceErrorCode.MEMBERSHIP_CONFLICT);
        }

        try {
            memberMapper.insertInvitation(workspaceId, userId, role.name(), actorUserId);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(WorkspaceErrorCode.MEMBERSHIP_CONFLICT);
        }
    }

    /** 邀请入口只接受现有 ACTIVE 账户；不存在的邮箱不会生成悬空成员记录。 */
    @Transactional
    public void inviteMemberByEmail(long workspaceId, String email, WorkspaceRole role) {
        long userId = userAccountService.findActiveUserIdByEmail(email)
                .orElseThrow(() -> new BusinessException(WorkspaceErrorCode.USER_NOT_ACTIVE));
        inviteMember(workspaceId, userId, role);
    }

    @Transactional
    public void activateMember(long workspaceId, long userId, long expectedVersion) {
        requireActiveUser(userId);
        if (memberMapper.activate(workspaceId, userId, expectedVersion) != 1) {
            throw new BusinessException(WorkspaceErrorCode.MEMBERSHIP_VERSION_CONFLICT);
        }
    }

    /** 仅当前受邀用户可接受自己的邀请，防止管理员或其他用户替代确认。 */
    @Transactional
    public void acceptOwnInvitation(long workspaceId, long expectedVersion) {
        activateMember(workspaceId, currentUserProvider.requireUserId(), expectedVersion);
    }

    /** 拒绝是终态而非删除，保留邀请审计信息且不能获得任何 workspace/project 权限。 */
    @Transactional
    public void rejectOwnInvitation(long workspaceId, long expectedVersion) {
        long userId = currentUserProvider.requireUserId();
        if (memberMapper.reject(workspaceId, userId, expectedVersion) != 1) {
            throw new BusinessException(WorkspaceErrorCode.MEMBERSHIP_VERSION_CONFLICT);
        }
    }

    @Transactional(readOnly = true)
    public List<WorkspaceMemberEntity> listMembers(long workspaceId) {
        authorizationService.requirePermission(currentUserProvider.requireUserId(), workspaceId,
                WorkspacePermission.WORKSPACE_MEMBER_LIST);
        return memberMapper.findByWorkspace(workspaceId);
    }

    @Transactional
    public void changeMemberRole(
            long workspaceId,
            long userId,
            WorkspaceRole role,
            long expectedVersion
    ) {
        long actorUserId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(
                actorUserId, workspaceId, WorkspacePermission.WORKSPACE_MEMBER_ROLE_UPDATE
        );
        requireAssignableMemberRole(role);
        requireNotOwner(workspaceId, userId);
        requireAdminAssignmentPolicy(actorUserId, workspaceId, role);

        WorkspaceMemberEntity member = requireMember(workspaceId, userId);
        if (actorUserId == userId || !mayManageMember(actorUserId, workspaceId, member)) {
            throw new BusinessException(IdentityErrorCode.ACCESS_DENIED);
        }
        if (memberMapper.changeRole(workspaceId, userId, role.name(), expectedVersion) != 1) {
            throw new BusinessException(WorkspaceErrorCode.MEMBERSHIP_VERSION_CONFLICT);
        }
    }

    @Transactional
    public void removeMember(long workspaceId, long userId, long expectedVersion) {
        long actorUserId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(
                actorUserId, workspaceId, WorkspacePermission.WORKSPACE_MEMBER_REMOVE
        );
        requireNotOwner(workspaceId, userId);
        WorkspaceMemberEntity member = requireMember(workspaceId, userId);
        if (!mayManageMember(actorUserId, workspaceId, member)) {
            throw new BusinessException(IdentityErrorCode.ACCESS_DENIED);
        }
        if (memberMapper.remove(workspaceId, userId, expectedVersion) != 1) {
            throw new BusinessException(WorkspaceErrorCode.MEMBERSHIP_VERSION_CONFLICT);
        }
        projectMemberMapper.removeAllForWorkspaceUser(workspaceId, userId);
    }

    @Transactional
    public void transferOwnership(long workspaceId, long newOwnerUserId, long expectedWorkspaceVersion) {
        long currentOwnerUserId = currentUserProvider.requireUserId();
        authorizationService.requirePermission(
                currentOwnerUserId,
                workspaceId,
                WorkspacePermission.WORKSPACE_TRANSFER_OWNERSHIP
        );
        if (currentOwnerUserId == newOwnerUserId) {
            throw new BusinessException(WorkspaceErrorCode.MEMBERSHIP_CONFLICT);
        }
        requireActiveUser(newOwnerUserId);

        if (workspaceMapper.transferOwnership(
                workspaceId,
                currentOwnerUserId,
                newOwnerUserId,
                expectedWorkspaceVersion
        ) != 1) {
            throw new BusinessException(WorkspaceErrorCode.OWNERSHIP_TRANSFER_CONFLICT);
        }
        memberMapper.removeForNewOwner(workspaceId, newOwnerUserId);
        memberMapper.upsertActiveAdmin(workspaceId, currentOwnerUserId, newOwnerUserId);
    }

    private void requireAssignableMemberRole(WorkspaceRole role) {
        if (role == null || role == WorkspaceRole.OWNER) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_WORKSPACE_ROLE);
        }
    }

    private void requireActiveUser(long userId) {
        if (!userAccountService.isActive(userId)) {
            throw new BusinessException(WorkspaceErrorCode.USER_NOT_ACTIVE);
        }
    }

    private void requireNotOwner(long workspaceId, long userId) {
        WorkspaceEntity workspace = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
        if (workspace.ownerUserId() != null && workspace.ownerUserId() == userId) {
            throw new BusinessException(IdentityErrorCode.ACCESS_DENIED);
        }
    }

    private WorkspaceMemberEntity requireMember(long workspaceId, long userId) {
        return memberMapper.findByWorkspaceAndUser(workspaceId, userId)
                .orElseThrow(() -> new BusinessException(WorkspaceErrorCode.MEMBERSHIP_CONFLICT));
    }

    private void requireAdminAssignmentPolicy(long actorUserId, long workspaceId, WorkspaceRole role) {
        WorkspaceRole actorRole = authorizationService.getEffectiveRole(actorUserId, workspaceId)
                .orElseThrow(() -> new BusinessException(IdentityErrorCode.ACCESS_DENIED));
        if (actorRole == WorkspaceRole.ADMIN && role == WorkspaceRole.ADMIN) {
            throw new BusinessException(IdentityErrorCode.ACCESS_DENIED);
        }
    }

    private boolean mayManageMember(
            long actorUserId,
            long workspaceId,
            WorkspaceMemberEntity targetMember
    ) {
        WorkspaceRole actorRole = authorizationService.getEffectiveRole(actorUserId, workspaceId)
                .orElseThrow(() -> new BusinessException(IdentityErrorCode.ACCESS_DENIED));
        return actorRole == WorkspaceRole.OWNER || !WorkspaceRole.ADMIN.name().equals(targetMember.role());
    }
}
