package com.obdeadsoup.devpilot.project.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.identity.application.UserAccountService;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import com.obdeadsoup.devpilot.project.api.dto.ProjectResponse;
import com.obdeadsoup.devpilot.project.domain.ProjectKey;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.domain.ProjectRole;
import com.obdeadsoup.devpilot.project.domain.ProjectStatus;
import com.obdeadsoup.devpilot.project.domain.ProjectVisibility;
import com.obdeadsoup.devpilot.project.domain.WorkspacePermission;
import com.obdeadsoup.devpilot.project.domain.WorkspaceRole;
import com.obdeadsoup.devpilot.project.domain.WorkspaceStatus;
import com.obdeadsoup.devpilot.project.error.ProjectErrorCode;
import com.obdeadsoup.devpilot.project.error.WorkspaceErrorCode;
import com.obdeadsoup.devpilot.project.persistence.entity.ProjectEntity;
import com.obdeadsoup.devpilot.project.persistence.entity.WorkspaceEntity;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.ProjectMemberMapper;
import com.obdeadsoup.devpilot.project.persistence.mapper.WorkspaceMapper;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class ProjectService {

    private final CurrentUserProvider currentUserProvider;
    private final UserAccountService userAccountService;
    private final WorkspaceAuthorizationService workspaceAuthorizationService;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final WorkspaceMapper workspaceMapper;
    private final ProjectMapper projectMapper;
    private final ProjectMemberMapper projectMemberMapper;

    public ProjectService(
            CurrentUserProvider currentUserProvider,
            UserAccountService userAccountService,
            WorkspaceAuthorizationService workspaceAuthorizationService,
            ProjectAuthorizationService projectAuthorizationService,
            WorkspaceMapper workspaceMapper,
            ProjectMapper projectMapper,
            ProjectMemberMapper projectMemberMapper
    ) {
        this.currentUserProvider = currentUserProvider;
        this.userAccountService = userAccountService;
        this.workspaceAuthorizationService = workspaceAuthorizationService;
        this.projectAuthorizationService = projectAuthorizationService;
        this.workspaceMapper = workspaceMapper;
        this.projectMapper = projectMapper;
        this.projectMemberMapper = projectMemberMapper;
    }

    @Transactional
    public ProjectResponse createProject(
            long workspaceId,
            String name,
            String rawProjectKey,
            String description,
            ProjectVisibility visibility
    ) {
        long userId = currentUserProvider.requireUserId();
        if (!userAccountService.isActive(userId)) {
            throw new BusinessException(WorkspaceErrorCode.USER_NOT_ACTIVE);
        }
        requireActiveWorkspace(workspaceId);
        workspaceAuthorizationService.requirePermission(
                userId, workspaceId, WorkspacePermission.PROJECT_CREATE
        );
        ProjectKey projectKey = ProjectKey.from(rawProjectKey);
        ProjectVisibility effectiveVisibility = visibility == null
                ? ProjectVisibility.PRIVATE
                : visibility;
        try {
            projectMapper.insert(
                    workspaceId,
                    name.strip(),
                    projectKey.value(),
                    normalizeDescription(description),
                    effectiveVisibility.name(),
                    userId
            );
        } catch (DuplicateKeyException exception) {
            throw new BusinessException(ProjectErrorCode.PROJECT_KEY_CONFLICT);
        }
        ProjectEntity project = projectMapper.findByKey(workspaceId, projectKey.value())
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
        WorkspaceRole workspaceRole = workspaceAuthorizationService
                .getEffectiveRole(userId, workspaceId)
                .orElseThrow(() -> new BusinessException(WorkspaceErrorCode.WORKSPACE_DISABLED));
        if (workspaceRole != WorkspaceRole.OWNER && workspaceRole != WorkspaceRole.ADMIN) {
            projectMemberMapper.insertActive(
                    workspaceId,
                    project.id(),
                    userId,
                    ProjectRole.PROJECT_ADMIN.name(),
                    userId
            );
        }
        return ProjectResponse.from(project);
    }

    @Transactional(readOnly = true)
    public ProjectResponse getProject(long workspaceId, long projectId) {
        return getProjectForActor(currentUserProvider.requireUserId(), workspaceId, projectId);
    }

    /**
     * 使用明确 actor 执行 Project 读取，供经过服务身份校验的委托调用复用；权限仍在能力执行点实时判断。
     */
    @Transactional(readOnly = true)
    public ProjectResponse getProjectForActor(long actorUserId, long workspaceId, long projectId) {
        projectAuthorizationService.requirePermission(
                actorUserId, workspaceId, projectId, ProjectPermission.PROJECT_READ
        );
        return ProjectResponse.from(requireProject(workspaceId, projectId));
    }

    @Transactional(readOnly = true)
    public PageResponse<ProjectResponse> listProjects(
            long workspaceId,
            int page,
            int size,
            ProjectStatus status,
            ProjectVisibility visibility
    ) {
        long userId = currentUserProvider.requireUserId();
        requireActiveWorkspace(workspaceId);
        workspaceAuthorizationService.requirePermission(
                userId, workspaceId, WorkspacePermission.WORKSPACE_READ
        );
        String statusName = status == null ? null : status.name();
        String visibilityName = visibility == null ? null : visibility.name();
        long total = projectMapper.countVisible(userId, workspaceId, statusName, visibilityName);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                page,
                size,
                total,
                projectMapper.findVisible(
                                userId,
                                workspaceId,
                                statusName,
                                visibilityName,
                                offset,
                                size
                        ).stream()
                        .map(ProjectResponse::from)
                        .toList()
        );
    }

    @Transactional
    public ProjectResponse updateProjectProfile(
            long workspaceId,
            long projectId,
            String name,
            String description,
            ProjectVisibility visibility,
            long expectedVersion
    ) {
        long userId = currentUserProvider.requireUserId();
        requireReadable(userId, workspaceId, projectId);
        ProjectEntity project = requireProject(workspaceId, projectId);
        if (ProjectStatus.ARCHIVED.name().equals(project.status())) {
            throw new BusinessException(ProjectErrorCode.PROJECT_ARCHIVED);
        }
        projectAuthorizationService.requirePermission(
                userId, workspaceId, projectId, ProjectPermission.PROJECT_UPDATE
        );
        requireExpectedVersion(project, expectedVersion);
        if (projectMapper.updateProfile(
                workspaceId,
                projectId,
                name.strip(),
                normalizeDescription(description),
                visibility.name(),
                expectedVersion
        ) != 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_VERSION_CONFLICT);
        }
        return ProjectResponse.from(requireProject(workspaceId, projectId));
    }

    @Transactional
    public ProjectResponse activateProject(long workspaceId, long projectId, long expectedVersion) {
        long userId = currentUserProvider.requireUserId();
        requireReadable(userId, workspaceId, projectId);
        ProjectEntity project = requireProject(workspaceId, projectId);
        requireExpectedVersion(project, expectedVersion);
        if (!ProjectStatus.PLANNING.name().equals(project.status())) {
            throw new BusinessException(ProjectErrorCode.INVALID_PROJECT_STATUS_TRANSITION);
        }
        projectAuthorizationService.requirePermission(
                userId, workspaceId, projectId, ProjectPermission.PROJECT_UPDATE
        );
        if (projectMapper.activate(workspaceId, projectId, expectedVersion) != 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_VERSION_CONFLICT);
        }
        return ProjectResponse.from(requireProject(workspaceId, projectId));
    }

    @Transactional
    public ProjectResponse archiveProject(long workspaceId, long projectId, long expectedVersion) {
        long userId = currentUserProvider.requireUserId();
        projectAuthorizationService.requirePermission(
                userId, workspaceId, projectId, ProjectPermission.PROJECT_ARCHIVE
        );
        ProjectEntity project = requireProject(workspaceId, projectId);
        requireExpectedVersion(project, expectedVersion);
        if (ProjectStatus.ARCHIVED.name().equals(project.status())) {
            throw new BusinessException(ProjectErrorCode.INVALID_PROJECT_STATUS_TRANSITION);
        }
        if (projectMapper.archive(workspaceId, projectId, expectedVersion) != 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_VERSION_CONFLICT);
        }
        return ProjectResponse.from(requireProject(workspaceId, projectId));
    }

    @Transactional
    public ProjectResponse restoreProject(long workspaceId, long projectId, long expectedVersion) {
        long userId = currentUserProvider.requireUserId();
        projectAuthorizationService.requirePermission(
                userId, workspaceId, projectId, ProjectPermission.PROJECT_ARCHIVE
        );
        ProjectEntity project = requireProject(workspaceId, projectId);
        requireExpectedVersion(project, expectedVersion);
        if (!ProjectStatus.ARCHIVED.name().equals(project.status())) {
            throw new BusinessException(ProjectErrorCode.INVALID_PROJECT_STATUS_TRANSITION);
        }
        if (projectMapper.restore(workspaceId, projectId, expectedVersion) != 1) {
            throw new BusinessException(ProjectErrorCode.PROJECT_VERSION_CONFLICT);
        }
        return ProjectResponse.from(requireProject(workspaceId, projectId));
    }

    private void requireReadable(long userId, long workspaceId, long projectId) {
        projectAuthorizationService.requirePermission(
                userId, workspaceId, projectId, ProjectPermission.PROJECT_READ
        );
    }

    private WorkspaceEntity requireActiveWorkspace(long workspaceId) {
        WorkspaceEntity workspace = workspaceMapper.findById(workspaceId)
                .orElseThrow(() -> new BusinessException(WorkspaceErrorCode.WORKSPACE_NOT_FOUND));
        if (!WorkspaceStatus.ACTIVE.name().equals(workspace.status())) {
            throw new BusinessException(WorkspaceErrorCode.WORKSPACE_DISABLED);
        }
        return workspace;
    }

    private ProjectEntity requireProject(long workspaceId, long projectId) {
        return projectMapper.findByScope(workspaceId, projectId)
                .orElseThrow(() -> new BusinessException(ProjectErrorCode.PROJECT_NOT_FOUND));
    }

    private void requireExpectedVersion(ProjectEntity project, long expectedVersion) {
        if (project.version() != expectedVersion) {
            throw new BusinessException(ProjectErrorCode.PROJECT_VERSION_CONFLICT);
        }
    }

    private String normalizeDescription(String description) {
        return description == null ? null : description.strip();
    }
}
