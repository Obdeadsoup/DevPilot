package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.identity.application.UserAccountService;
import com.obdeadsoup.devpilot.identity.error.IdentityErrorCode;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import com.obdeadsoup.devpilot.project.api.dto.WorkspaceResponse;
import com.obdeadsoup.devpilot.project.domain.WorkspacePermission;
import com.obdeadsoup.devpilot.project.domain.WorkspaceStatus;
import com.obdeadsoup.devpilot.project.error.WorkspaceErrorCode;
import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceEntity;
import com.obdeadsoup.devpilot.project.persistence.mapper.WorkspaceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class WorkspaceService {

    private static final Pattern VALID_SLUG = Pattern.compile("[a-z0-9](?:[a-z0-9-]{1,62}[a-z0-9])?");

    private final CurrentUserProvider currentUserProvider;
    private final UserAccountService userAccountService;
    private final WorkspaceAuthorizationService authorizationService;
    private final WorkspaceMapper workspaceMapper;

    public WorkspaceService(
            CurrentUserProvider currentUserProvider,
            UserAccountService userAccountService,
            WorkspaceAuthorizationService authorizationService,
            WorkspaceMapper workspaceMapper
    ) {
        this.currentUserProvider = currentUserProvider;
        this.userAccountService = userAccountService;
        this.authorizationService = authorizationService;
        this.workspaceMapper = workspaceMapper;
    }

    @Transactional
    public WorkspaceResponse createWorkspace(String name, String slug, String description) {
        long userId = currentUserProvider.requireUserId();
        if (!userAccountService.isActive(userId)) {
            throw new BusinessException(WorkspaceErrorCode.USER_NOT_ACTIVE);
        }
        String normalizedSlug = normalizeSlug(slug);
        try {
            workspaceMapper.insert(name.strip(), normalizedSlug, normalizeDescription(description), userId);
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(WorkspaceErrorCode.WORKSPACE_SLUG_CONFLICT);
        }
        return workspaceMapper.findBySlug(normalizedSlug)
                .map(WorkspaceResponse::from)
                .orElseThrow(() -> new BusinessException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
    }

    @Transactional(readOnly = true)
    public WorkspaceResponse getWorkspace(long workspaceId) {
        long userId = currentUserProvider.requireUserId();
        WorkspaceEntity workspace = requireWorkspace(workspaceId);
        if (!isOwner(workspace, userId)) {
            authorizationService.requirePermission(userId, workspaceId, WorkspacePermission.WORKSPACE_READ);
        }
        return WorkspaceResponse.from(workspace);
    }

    @Transactional(readOnly = true)
    public PageResponse<WorkspaceResponse> listMyWorkspaces(int page, int size) {
        long userId = currentUserProvider.requireUserId();
        long total = workspaceMapper.countMine(userId);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                page,
                size,
                total,
                workspaceMapper.findMine(userId, offset, size).stream()
                        .map(WorkspaceResponse::from)
                        .toList()
        );
    }

    @Transactional
    public WorkspaceResponse updateWorkspaceProfile(
            long workspaceId,
            String name,
            String description,
            long expectedVersion
    ) {
        long userId = currentUserProvider.requireUserId();
        WorkspaceEntity workspace = requireWorkspace(workspaceId);
        requireActiveForMutation(workspace, userId);
        authorizationService.requirePermission(userId, workspaceId, WorkspacePermission.WORKSPACE_UPDATE);
        if (workspaceMapper.updateProfile(
                workspaceId,
                name.strip(),
                normalizeDescription(description),
                expectedVersion
        ) != 1) {
            throw new BusinessException(WorkspaceErrorCode.WORKSPACE_VERSION_CONFLICT);
        }
        return WorkspaceResponse.from(requireWorkspace(workspaceId));
    }

    @Transactional
    public WorkspaceResponse disableWorkspace(long workspaceId, long expectedVersion) {
        long userId = currentUserProvider.requireUserId();
        WorkspaceEntity workspace = requireWorkspace(workspaceId);
        requireOwner(workspace, userId);
        if (!WorkspaceStatus.ACTIVE.name().equals(workspace.status())) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_WORKSPACE_STATUS_TRANSITION);
        }
        if (workspaceMapper.disable(workspaceId, userId, expectedVersion) != 1) {
            throw new BusinessException(WorkspaceErrorCode.WORKSPACE_VERSION_CONFLICT);
        }
        return WorkspaceResponse.from(requireWorkspace(workspaceId));
    }

    @Transactional
    public WorkspaceResponse reactivateWorkspace(long workspaceId, long expectedVersion) {
        long userId = currentUserProvider.requireUserId();
        WorkspaceEntity workspace = requireWorkspace(workspaceId);
        requireOwner(workspace, userId);
        if (!WorkspaceStatus.DISABLED.name().equals(workspace.status())) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_WORKSPACE_STATUS_TRANSITION);
        }
        if (workspaceMapper.reactivate(workspaceId, userId, expectedVersion) != 1) {
            throw new BusinessException(WorkspaceErrorCode.WORKSPACE_VERSION_CONFLICT);
        }
        return WorkspaceResponse.from(requireWorkspace(workspaceId));
    }

    private WorkspaceEntity requireWorkspace(long workspaceId) {
        return workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
    }

    private void requireActiveForMutation(WorkspaceEntity workspace, long userId) {
        if (!WorkspaceStatus.ACTIVE.name().equals(workspace.status())) {
            if (isOwner(workspace, userId)) {
                throw new BusinessException(WorkspaceErrorCode.WORKSPACE_DISABLED);
            }
            throw new BusinessException(IdentityErrorCode.ACCESS_DENIED);
        }
    }

    private void requireOwner(WorkspaceEntity workspace, long userId) {
        if (!isOwner(workspace, userId)) {
            throw new BusinessException(IdentityErrorCode.ACCESS_DENIED);
        }
    }

    private boolean isOwner(WorkspaceEntity workspace, long userId) {
        return workspace.ownerUserId() != null && workspace.ownerUserId() == userId;
    }

    private String normalizeSlug(String slug) {
        String normalized = slug == null ? "" : slug.strip().toLowerCase(Locale.ROOT);
        if (!VALID_SLUG.matcher(normalized).matches()) {
            throw new BusinessException(WorkspaceErrorCode.INVALID_WORKSPACE_SLUG);
        }
        return normalized;
    }

    private String normalizeDescription(String description) {
        return description == null ? null : description.strip();
    }
}
