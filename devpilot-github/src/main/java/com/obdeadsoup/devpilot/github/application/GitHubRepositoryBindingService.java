package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.api.dto.GitHubRepositoryResponse;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.application.client.VerifiedGitHubRepository;
import com.obdeadsoup.devpilot.github.application.credential.GitHubApiCredentialResolver;
import com.obdeadsoup.devpilot.github.application.secret.WebhookSecretResolver;
import com.obdeadsoup.devpilot.github.domain.GitHubRepositoryReference;
import com.obdeadsoup.devpilot.github.domain.GitHubRepositoryStatus;
import com.obdeadsoup.devpilot.github.error.GitHubRepositoryErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubRepositoryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import com.obdeadsoup.devpilot.identity.application.CurrentUserProvider;
import com.obdeadsoup.devpilot.project.api.dto.PageResponse;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDateTime;
import java.util.Optional;

@Service
public class GitHubRepositoryBindingService {

    private final CurrentUserProvider currentUserProvider;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final GitHubRepositoryMapper repositoryMapper;
    private final GitHubApiCredentialResolver apiCredentialResolver;
    private final WebhookSecretResolver webhookSecretResolver;
    private final GitHubRepositoryMetadataClient metadataClient;
    private final Clock clock;

    public GitHubRepositoryBindingService(
            CurrentUserProvider currentUserProvider,
            ProjectAuthorizationService projectAuthorizationService,
            GitHubRepositoryMapper repositoryMapper,
            GitHubApiCredentialResolver apiCredentialResolver,
            WebhookSecretResolver webhookSecretResolver,
            GitHubRepositoryMetadataClient metadataClient,
            Clock clock
    ) {
        this.currentUserProvider = currentUserProvider;
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryMapper = repositoryMapper;
        this.apiCredentialResolver = apiCredentialResolver;
        this.webhookSecretResolver = webhookSecretResolver;
        this.metadataClient = metadataClient;
        this.clock = clock;
    }

    @Transactional
    public GitHubRepositoryResponse bindRepository(
            long workspaceId,
            long projectId,
            String owner,
            String repositoryName,
            String apiCredentialRef,
            String webhookSecretRef
    ) {
        long userId = requirePermission(workspaceId, projectId, ProjectPermission.REPOSITORY_BIND);
        GitHubRepositoryReference reference = GitHubRepositoryReference.from(owner, repositoryName);
        String apiToken = resolveApiCredential(apiCredentialRef);
        VerifiedGitHubRepository verified = metadataClient.getRepository(
                reference.owner(), reference.repositoryName(), apiToken
        );
        requireWebhookSecret(webhookSecretRef);
        requireNotAlreadyBound(workspaceId, projectId, verified);
        try {
            repositoryMapper.insert(
                    workspaceId,
                    projectId,
                    verified.githubRepositoryId(),
                    verified.ownerLogin(),
                    verified.repositoryName(),
                    verified.fullName(),
                    verified.htmlUrl(),
                    verified.defaultBranch(),
                    verified.visibility(),
                    webhookSecretRef,
                    apiCredentialRef,
                    LocalDateTime.now(clock),
                    userId
            );
        } catch (DuplicateKeyException exception) {
            throw duplicateBinding(workspaceId, projectId, verified);
        }
        return GitHubRepositoryResponse.from(
                repositoryMapper.findByGitHubRepositoryId(verified.githubRepositoryId())
                        .filter(entity -> entity.workspaceId() == workspaceId && entity.projectId() == projectId)
                        .orElseThrow(() -> new BusinessException(
                                GitHubRepositoryErrorCode.REPOSITORY_BINDING_NOT_FOUND
                        ))
        );
    }

    @Transactional(readOnly = true)
    public PageResponse<GitHubRepositoryResponse> listRepositories(
            long workspaceId,
            long projectId,
            int page,
            int size,
            GitHubRepositoryStatus status
    ) {
        requirePermission(workspaceId, projectId, ProjectPermission.REPOSITORY_READ);
        String statusName = status == null ? null : status.name();
        long total = repositoryMapper.countByProject(workspaceId, projectId, statusName);
        long offset = (long) (page - 1) * size;
        return new PageResponse<>(
                page,
                size,
                total,
                repositoryMapper.findByProject(workspaceId, projectId, statusName, offset, size)
                        .stream()
                        .map(GitHubRepositoryResponse::from)
                        .toList()
        );
    }

    @Transactional(readOnly = true)
    public GitHubRepositoryResponse getRepository(long workspaceId, long projectId, long bindingId) {
        requirePermission(workspaceId, projectId, ProjectPermission.REPOSITORY_READ);
        return GitHubRepositoryResponse.from(requireBinding(workspaceId, projectId, bindingId));
    }

    @Transactional
    public GitHubRepositoryResponse disableRepository(
            long workspaceId,
            long projectId,
            long bindingId,
            long expectedVersion
    ) {
        requirePermission(workspaceId, projectId, ProjectPermission.REPOSITORY_UPDATE);
        GitHubRepositoryEntity binding = requireBinding(workspaceId, projectId, bindingId);
        requireExpectedVersion(binding, expectedVersion);
        if (GitHubRepositoryStatus.DISABLED.name().equals(binding.bindingStatus())) {
            throw new BusinessException(GitHubRepositoryErrorCode.REPOSITORY_BINDING_DISABLED);
        }
        if (!GitHubRepositoryStatus.ACTIVE.name().equals(binding.bindingStatus())) {
            throw new BusinessException(GitHubRepositoryErrorCode.INVALID_BINDING_STATUS_TRANSITION);
        }
        if (repositoryMapper.disable(workspaceId, projectId, bindingId, expectedVersion) != 1) {
            throw versionConflict();
        }
        return GitHubRepositoryResponse.from(requireBinding(workspaceId, projectId, bindingId));
    }

    @Transactional
    public GitHubRepositoryResponse reactivateRepository(
            long workspaceId,
            long projectId,
            long bindingId,
            long expectedVersion
    ) {
        requirePermission(workspaceId, projectId, ProjectPermission.REPOSITORY_UPDATE);
        GitHubRepositoryEntity binding = requireBinding(workspaceId, projectId, bindingId);
        requireExpectedVersion(binding, expectedVersion);
        if (!GitHubRepositoryStatus.DISABLED.name().equals(binding.bindingStatus())) {
            throw new BusinessException(GitHubRepositoryErrorCode.INVALID_BINDING_STATUS_TRANSITION);
        }
        String apiToken = resolveApiCredential(binding.apiCredentialRef());
        requireWebhookSecret(binding.webhookSecretRef());
        VerifiedGitHubRepository verified = metadataClient.getRepository(
                binding.ownerLogin(), binding.repositoryName(), apiToken
        );
        requireSameRepository(binding, verified);
        try {
            if (repositoryMapper.reactivate(
                    workspaceId,
                    projectId,
                    bindingId,
                    expectedVersion,
                    verified.ownerLogin(),
                    verified.repositoryName(),
                    verified.fullName(),
                    verified.htmlUrl(),
                    verified.defaultBranch(),
                    verified.visibility(),
                    LocalDateTime.now(clock)
            ) != 1) {
                throw versionConflict();
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateBinding(workspaceId, projectId, verified);
        }
        return GitHubRepositoryResponse.from(requireBinding(workspaceId, projectId, bindingId));
    }

    @Transactional
    public GitHubRepositoryResponse refreshRepositoryMetadata(
            long workspaceId,
            long projectId,
            long bindingId,
            long expectedVersion
    ) {
        requirePermission(workspaceId, projectId, ProjectPermission.REPOSITORY_UPDATE);
        GitHubRepositoryEntity binding = requireBinding(workspaceId, projectId, bindingId);
        requireExpectedVersion(binding, expectedVersion);
        requireKnownStatus(binding);
        String apiToken = resolveApiCredential(binding.apiCredentialRef());
        VerifiedGitHubRepository verified = metadataClient.getRepository(
                binding.ownerLogin(), binding.repositoryName(), apiToken
        );
        requireSameRepository(binding, verified);
        try {
            if (repositoryMapper.refreshMetadata(
                    workspaceId,
                    projectId,
                    bindingId,
                    expectedVersion,
                    verified.ownerLogin(),
                    verified.repositoryName(),
                    verified.fullName(),
                    verified.htmlUrl(),
                    verified.defaultBranch(),
                    verified.visibility(),
                    LocalDateTime.now(clock)
            ) != 1) {
                throw versionConflict();
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateBinding(workspaceId, projectId, verified);
        }
        return GitHubRepositoryResponse.from(requireBinding(workspaceId, projectId, bindingId));
    }

    @Transactional
    public void unbindRepository(
            long workspaceId,
            long projectId,
            long bindingId,
            long expectedVersion
    ) {
        requirePermission(workspaceId, projectId, ProjectPermission.REPOSITORY_UNBIND);
        GitHubRepositoryEntity binding = requireBinding(workspaceId, projectId, bindingId);
        requireExpectedVersion(binding, expectedVersion);
        requireKnownStatus(binding);
        if (repositoryMapper.unbind(workspaceId, projectId, bindingId, expectedVersion) != 1) {
            throw versionConflict();
        }
    }

    private long requirePermission(long workspaceId, long projectId, ProjectPermission permission) {
        long userId = currentUserProvider.requireUserId();
        projectAuthorizationService.requirePermission(userId, workspaceId, projectId, permission);
        return userId;
    }

    private String resolveApiCredential(String credentialReference) {
        return apiCredentialResolver.resolve(credentialReference)
                .orElseThrow(() -> new BusinessException(
                        GitHubRepositoryErrorCode.GITHUB_API_CREDENTIAL_UNAVAILABLE
                ));
    }

    private void requireWebhookSecret(String credentialReference) {
        if (webhookSecretResolver.resolve(credentialReference).isEmpty()) {
            throw new BusinessException(GitHubRepositoryErrorCode.WEBHOOK_SECRET_UNAVAILABLE);
        }
    }

    private void requireNotAlreadyBound(
            long workspaceId,
            long projectId,
            VerifiedGitHubRepository verified
    ) {
        Optional<GitHubRepositoryEntity> existing = repositoryMapper.findByGitHubRepositoryId(
                verified.githubRepositoryId()
        );
        if (existing.isEmpty()) {
            existing = repositoryMapper.findActiveByWorkspaceAndFullName(workspaceId, verified.fullName());
        }
        existing.ifPresent(binding -> {
            throw bindingConflict(workspaceId, projectId, binding);
        });
    }

    private BusinessException duplicateBinding(
            long workspaceId,
            long projectId,
            VerifiedGitHubRepository verified
    ) {
        Optional<GitHubRepositoryEntity> existing = repositoryMapper
                .findActiveByGitHubRepositoryIdForUpdate(verified.githubRepositoryId());
        if (existing.isEmpty()) {
            existing = repositoryMapper.findActiveByWorkspaceAndFullNameForUpdate(
                    workspaceId, verified.fullName()
            );
        }
        return existing
                .map(binding -> bindingConflict(workspaceId, projectId, binding))
                .orElseGet(() -> new BusinessException(
                        GitHubRepositoryErrorCode.REPOSITORY_ALREADY_BOUND
                ));
    }

    private BusinessException bindingConflict(
            long workspaceId,
            long projectId,
            GitHubRepositoryEntity existing
    ) {
        GitHubRepositoryErrorCode errorCode = existing.workspaceId() == workspaceId
                && existing.projectId() == projectId
                ? GitHubRepositoryErrorCode.REPOSITORY_ALREADY_BOUND
                : GitHubRepositoryErrorCode.REPOSITORY_BOUND_TO_ANOTHER_PROJECT;
        return new BusinessException(errorCode);
    }

    private GitHubRepositoryEntity requireBinding(long workspaceId, long projectId, long bindingId) {
        return repositoryMapper.findByScope(workspaceId, projectId, bindingId)
                .orElseThrow(() -> new BusinessException(
                        GitHubRepositoryErrorCode.REPOSITORY_BINDING_NOT_FOUND
                ));
    }

    private void requireExpectedVersion(GitHubRepositoryEntity binding, long expectedVersion) {
        if (binding.version() != expectedVersion) {
            throw versionConflict();
        }
    }

    private void requireKnownStatus(GitHubRepositoryEntity binding) {
        if (!GitHubRepositoryStatus.ACTIVE.name().equals(binding.bindingStatus())
                && !GitHubRepositoryStatus.DISABLED.name().equals(binding.bindingStatus())) {
            throw new BusinessException(GitHubRepositoryErrorCode.INVALID_BINDING_STATUS_TRANSITION);
        }
    }

    private void requireSameRepository(
            GitHubRepositoryEntity binding,
            VerifiedGitHubRepository verified
    ) {
        if (binding.githubRepositoryId() != verified.githubRepositoryId()) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_REPOSITORY_ID_MISMATCH);
        }
    }

    private BusinessException versionConflict() {
        return new BusinessException(GitHubRepositoryErrorCode.REPOSITORY_BINDING_VERSION_CONFLICT);
    }
}
