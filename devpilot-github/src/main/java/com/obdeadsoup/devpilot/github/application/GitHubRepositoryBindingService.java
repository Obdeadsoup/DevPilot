package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.api.dto.GitHubRepositoryResponse;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiException;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiResponse;
import com.obdeadsoup.devpilot.github.application.client.GitHubConditionalRequest;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.application.client.GitHubBranch;
import com.obdeadsoup.devpilot.github.application.client.GitHubBranchClient;
import com.obdeadsoup.devpilot.github.application.client.VerifiedGitHubRepository;
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
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * GitHub Repository Binding 的 Project 作用域 Application Service。
 *
 * <p>负责 RBAC、可信 Metadata、凭据可用性、生命周期、乐观锁和活动 Binding 唯一冲突；
 * Controller、后台任务或未来 Agent 都不能绕过本服务直接写 Mapper。</p>
 */
@Service
public class GitHubRepositoryBindingService {

    private final CurrentUserProvider currentUserProvider;
    private final ProjectAuthorizationService projectAuthorizationService;
    private final GitHubRepositoryMapper repositoryMapper;
    private final WebhookSecretResolver webhookSecretResolver;
    private final GitHubRepositoryMetadataClient metadataClient;
    private final GitHubBranchClient branchClient;
    private final Clock clock;

    public GitHubRepositoryBindingService(
            CurrentUserProvider currentUserProvider,
            ProjectAuthorizationService projectAuthorizationService,
            GitHubRepositoryMapper repositoryMapper,
            WebhookSecretResolver webhookSecretResolver,
            GitHubRepositoryMetadataClient metadataClient,
            GitHubBranchClient branchClient,
            Clock clock
    ) {
        this.currentUserProvider = currentUserProvider;
        this.projectAuthorizationService = projectAuthorizationService;
        this.repositoryMapper = repositoryMapper;
        this.webhookSecretResolver = webhookSecretResolver;
        this.metadataClient = metadataClient;
        this.branchClient = branchClient;
        this.clock = clock;
    }

    /** 仅已授权的项目成员可实时读取绑定仓库分支；凭据只在服务端统一执行器中解析。 */
    @Transactional(readOnly = true)
    public java.util.List<GitHubBranch> listBranches(long workspaceId, long projectId, long bindingId) {
        requirePermission(workspaceId, projectId, ProjectPermission.REPOSITORY_READ);
        GitHubRepositoryEntity binding = requireBinding(workspaceId, projectId, bindingId);
        requireKnownStatus(binding);
        return listBranches(binding);
    }

    /**
     * 为已通过 AGENT_PROPOSE 授权的 Run 解析 ACTIVE Repository 的代码快照。
     *
     * <p>没有 ACTIVE Binding 时返回 empty，保持原有无 GitHub 上下文的 Agent Run 能力；请求显式
     * branch 时不会回退到默认分支，只有 branchName 缺失才使用 Binding 的 defaultBranch。commit SHA
     * 在此刻解析并由调用方冻结，后续 Tool Gateway 不会重新读取 branch HEAD。</p>
     */
    @Transactional(readOnly = true)
    public Optional<GitHubRepositoryBranchSnapshot> resolveActiveBranchSnapshotForAgentRun(
            long workspaceId, long projectId, String requestedBranchName
    ) {
        Optional<GitHubRepositoryEntity> activeBinding = repositoryMapper.findActiveByProject(workspaceId, projectId);
        if (activeBinding.isEmpty()) {
            return Optional.empty();
        }
        GitHubRepositoryEntity binding = activeBinding.get();
        String branchName = requestedBranchName == null ? binding.defaultBranch() : requestedBranchName;
        GitHubBranch branch = listBranches(binding).stream()
                .filter(candidate -> candidate.name().equals(branchName))
                .findFirst()
                .orElseThrow(() -> new BusinessException(GitHubRepositoryErrorCode.GITHUB_BRANCH_NOT_FOUND));
        return Optional.of(new GitHubRepositoryBranchSnapshot(
                binding.fullName(), branch.name(), branch.commitSha()
        ));
    }

    /**
     * 为当前 Project 创建 ACTIVE Binding，只写入 GitHub API 返回的权威身份和元数据。
     *
     * <p>调用前要求 REPOSITORY_BIND；Token 在 HTTP Executor 中解析，Webhook Secret 在落库前验证。
     * 应用层查重提供友好错误，数据库活动唯一索引负责并发最终仲裁。</p>
     */
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
        GitHubApiResponse<VerifiedGitHubRepository> metadata = fetchMetadata(
                reference.owner(), reference.repositoryName(), apiCredentialRef,
                GitHubConditionalRequest.none()
        );
        VerifiedGitHubRepository verified = requireRepositoryBody(metadata);
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
                    metadata.etag(),
                    toLocalDateTime(metadata.lastModified()),
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

    /**
     * 将 ACTIVE Binding 置为 DISABLED，并使用 Scope、状态和 expectedVersion 条件防止并发覆盖。
     */
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

    /**
     * 将 DISABLED Binding 恢复为 ACTIVE；恢复前重新访问 GitHub 并校验稳定 Repository ID。
     * 状态、Scope 和 expectedVersion 在同一条 UPDATE 中再次检查。
     */
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
        requireWebhookSecret(binding.webhookSecretRef());
        GitHubApiResponse<VerifiedGitHubRepository> metadata = fetchMetadata(
                binding.ownerLogin(), binding.repositoryName(), binding.apiCredentialRef(),
                GitHubConditionalRequest.none()
        );
        VerifiedGitHubRepository verified = requireRepositoryBody(metadata);
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
                    LocalDateTime.now(clock),
                    metadata.etag(),
                    toLocalDateTime(metadata.lastModified())
            ) != 1) {
                throw versionConflict();
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateBinding(workspaceId, projectId, verified);
        }
        return GitHubRepositoryResponse.from(requireBinding(workspaceId, projectId, bindingId));
    }

    /**
     * 使用 Binding 保存的 ETag/Last-Modified 刷新可信 Metadata。
     *
     * <p>200 会校验 Repository ID、替换权威字段和校验器；304 不覆盖元数据，只更新
     * last_verified_at 并 version+1。两条路径都使用 Scope、状态与 expectedVersion 条件更新。</p>
     */
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
        GitHubApiResponse<VerifiedGitHubRepository> metadata = fetchMetadata(
                binding.ownerLogin(),
                binding.repositoryName(),
                binding.apiCredentialRef(),
                new GitHubConditionalRequest(
                        binding.metadataEtag(),
                        toInstant(binding.metadataLastModified())
                )
        );
        LocalDateTime verifiedAt = LocalDateTime.now(clock);
        if (metadata.notModified()) {
            if (repositoryMapper.markMetadataNotModified(
                    workspaceId, projectId, bindingId, expectedVersion, verifiedAt
            ) != 1) {
                throw versionConflict();
            }
            return GitHubRepositoryResponse.from(requireBinding(workspaceId, projectId, bindingId));
        }
        VerifiedGitHubRepository verified = requireRepositoryBody(metadata);
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
                    verifiedAt,
                    metadata.etag(),
                    toLocalDateTime(metadata.lastModified())
            ) != 1) {
                throw versionConflict();
            }
        } catch (DuplicateKeyException exception) {
            throw duplicateBinding(workspaceId, projectId, verified);
        }
        return GitHubRepositoryResponse.from(requireBinding(workspaceId, projectId, bindingId));
    }

    /**
     * 软删除 ACTIVE 或 DISABLED Binding，并保留 Delivery/Activity 历史。
     * 更新以 expectedVersion 和当前状态为条件，零行更新转换为稳定版本冲突。
     */
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

    private void requireWebhookSecret(String credentialReference) {
        if (webhookSecretResolver.resolve(credentialReference).isEmpty()) {
            throw new BusinessException(GitHubRepositoryErrorCode.WEBHOOK_SECRET_UNAVAILABLE);
        }
    }

    private GitHubApiResponse<VerifiedGitHubRepository> fetchMetadata(
            String owner,
            String repositoryName,
            String credentialReference,
            GitHubConditionalRequest conditionalRequest
    ) {
        try {
            return metadataClient.getRepository(
                    owner, repositoryName, credentialReference, conditionalRequest
            );
        } catch (GitHubApiException exception) {
            throw mapApiFailure(exception);
        }
    }

    private VerifiedGitHubRepository requireRepositoryBody(
            GitHubApiResponse<VerifiedGitHubRepository> response
    ) {
        if (response == null || response.notModified() || response.body() == null) {
            throw new BusinessException(GitHubRepositoryErrorCode.GITHUB_API_RESPONSE_INVALID);
        }
        return response.body();
    }

    private BusinessException mapApiFailure(GitHubApiException exception) {
        GitHubRepositoryErrorCode errorCode = switch (exception.failureType()) {
            case CREDENTIAL_UNAVAILABLE -> GitHubRepositoryErrorCode.GITHUB_API_CREDENTIAL_UNAVAILABLE;
            case AUTHENTICATION -> GitHubRepositoryErrorCode.GITHUB_API_AUTHENTICATION_FAILED;
            case ACCESS_DENIED -> GitHubRepositoryErrorCode.GITHUB_API_FORBIDDEN;
            case NOT_FOUND, VALIDATION, CONFLICT ->
                    GitHubRepositoryErrorCode.GITHUB_REPOSITORY_NOT_ACCESSIBLE;
            case RATE_LIMITED -> GitHubRepositoryErrorCode.GITHUB_API_RATE_LIMITED;
            case MALFORMED_RESPONSE -> GitHubRepositoryErrorCode.GITHUB_API_RESPONSE_INVALID;
            case NETWORK_ERROR, TRANSIENT_SERVER_ERROR, CONCURRENCY_LIMITED ->
                    GitHubRepositoryErrorCode.GITHUB_API_UNAVAILABLE;
        };
        return new BusinessException(errorCode);
    }

    private java.util.List<GitHubBranch> listBranches(GitHubRepositoryEntity binding) {
        try {
            return branchClient.listBranches(binding.ownerLogin(), binding.repositoryName(), binding.apiCredentialRef());
        } catch (GitHubApiException exception) {
            throw mapApiFailure(exception);
        }
    }

    private LocalDateTime toLocalDateTime(Instant instant) {
        return instant == null ? null : LocalDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private Instant toInstant(LocalDateTime dateTime) {
        return dateTime == null ? null : dateTime.toInstant(ZoneOffset.UTC);
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
