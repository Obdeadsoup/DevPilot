package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiResponse;
import com.obdeadsoup.devpilot.github.application.client.GitHubCommit;
import com.obdeadsoup.devpilot.github.application.client.GitHubCommitClient;
import com.obdeadsoup.devpilot.github.application.client.GitHubConditionalRequest;
import com.obdeadsoup.devpilot.github.application.client.GitHubPage;
import com.obdeadsoup.devpilot.github.application.client.GitHubPageCursor;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.application.client.VerifiedGitHubRepository;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubCommitCommand;
import com.obdeadsoup.devpilot.github.config.GitHubReconciliationProperties;
import com.obdeadsoup.devpilot.github.domain.GitHubCommitSource;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncCheckpointEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncTarget;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubRepositoryMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Optional;

/**
 * Commit 对账编排服务：claim Run、校验可信 Repository、按 Link 分页读取并逐条走统一 Upsert。
 * 本类刻意不使用长 {@code @Transactional}，网络和多页循环均在事务外；每条 Commit、每页进度、
 * 最终 Checkpoint + SUCCEEDED 分别由短事务服务提交，避免慢网络长期占用连接或数据库锁。
 */
@Service
public class GitHubCommitReconciliationService {

    private static final Logger LOGGER = LoggerFactory.getLogger(GitHubCommitReconciliationService.class);

    private final GitHubSyncRunStateService runStateService;
    private final GitHubRepositoryMapper repositoryMapper;
    private final GitHubRepositoryMetadataClient metadataClient;
    private final GitHubCommitClient commitClient;
    private final GitHubSyncCheckpointService checkpointService;
    private final GitHubCommitApplicationService commitApplicationService;
    private final GitHubSyncFailureClassifier failureClassifier;
    private final GitHubReconciliationProperties properties;
    private final Clock clock;

    public GitHubCommitReconciliationService(
            GitHubSyncRunStateService runStateService,
            GitHubRepositoryMapper repositoryMapper,
            GitHubRepositoryMetadataClient metadataClient,
            GitHubCommitClient commitClient,
            GitHubSyncCheckpointService checkpointService,
            GitHubCommitApplicationService commitApplicationService,
            GitHubSyncFailureClassifier failureClassifier,
            GitHubReconciliationProperties properties,
            Clock clock
    ) {
        this.runStateService = runStateService;
        this.repositoryMapper = repositoryMapper;
        this.metadataClient = metadataClient;
        this.commitClient = commitClient;
        this.checkpointService = checkpointService;
        this.commitApplicationService = commitApplicationService;
        this.failureClassifier = failureClassifier;
        this.properties = properties;
        this.clock = clock;
    }

    /** 执行一个候选 Run；claim 失败说明其他实例已处理，安全返回。 */
    public void reconcile(long runId) {
        Optional<GitHubSyncRunEntity> claimed = runStateService.claim(runId);
        if (claimed.isEmpty()) {
            return;
        }
        GitHubSyncRunEntity run = claimed.get();
        try {
            reconcileClaimed(run);
        } catch (RuntimeException exception) {
            GitHubSyncFailureClassifier.Classification failure = failureClassifier.classify(exception);
            String result = runStateService.handleFailure(run, failure)
                    .map(Enum::name)
                    .orElse("UNCHANGED");
            LOGGER.warn(
                    "GitHub commit reconciliation failed runId={} bindingId={} errorCode={} "
                            + "exceptionType={} resultStatus={}",
                    run.id(), run.repositoryBindingId(), failure.stableErrorCode(),
                    exception.getClass().getName(), result
            );
        }
    }

    private void reconcileClaimed(GitHubSyncRunEntity run) {
        GitHubSyncTarget target = repositoryMapper.findSyncTarget(run.repositoryBindingId())
                .filter(GitHubSyncTarget::isEligible)
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SYNC_TARGET_UNAVAILABLE));
        GitHubSyncCheckpointEntity checkpoint = checkpointService.getOrCreate(target.bindingId());
        Instant since = checkpointService.calculateSince(checkpoint, clock.instant());

        GitHubApiResponse<VerifiedGitHubRepository> metadata = metadataClient.getRepository(
                target.ownerLogin(), target.repositoryName(), target.apiCredentialRef(),
                GitHubConditionalRequest.none()
        );
        if (metadata.body() == null
                || metadata.body().githubRepositoryId() != target.githubRepositoryId()) {
            throw new BusinessException(GitHubSyncErrorCode.REPOSITORY_ID_MISMATCH);
        }

        GitHubPageCursor cursor = GitHubPageCursor.empty();
        LocalDateTime reliableBoundary = null;
        String lastSeenSha = checkpoint.lastSeenCommitSha();
        do {
            GitHubPage<GitHubCommit> page = commitClient.listCommits(
                    target.ownerLogin(), target.repositoryName(), since, properties.perPage(),
                    target.apiCredentialRef(), cursor
            );
            for (GitHubCommit commit : page.items()) {
                commitApplicationService.upsert(toCommand(target, commit));
                LocalDateTime committedAt = LocalDateTime.ofInstant(commit.committedAt(), ZoneOffset.UTC);
                if (reliableBoundary == null || committedAt.isAfter(reliableBoundary)) {
                    reliableBoundary = committedAt;
                }
                lastSeenSha = commit.sha();
            }
            // 只有本页所有 Commit 的短事务都成功后，才能留下页级安全进度。
            checkpoint = checkpointService.recordPage(checkpoint, lastSeenSha);
            cursor = page.cursor();
        } while (cursor.hasNext());

        runStateService.complete(run, checkpoint, reliableBoundary, lastSeenSha);
    }

    private UpsertGitHubCommitCommand toCommand(GitHubSyncTarget target, GitHubCommit commit) {
        return new UpsertGitHubCommitCommand(
                target.workspaceId(),
                target.projectId(),
                target.bindingId(),
                target.githubRepositoryId(),
                target.fullName(),
                commit.sha(),
                commit.message(),
                commit.authorName(),
                commit.authorEmail(),
                commit.authorGitHubUserId(),
                commit.authorLogin(),
                LocalDateTime.ofInstant(commit.committedAt(), ZoneOffset.UTC),
                commit.htmlUrl(),
                GitHubCommitSource.API
        );
    }
}
