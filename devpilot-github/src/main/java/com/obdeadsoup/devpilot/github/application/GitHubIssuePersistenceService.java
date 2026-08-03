package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubIssueCommand;
import com.obdeadsoup.devpilot.github.domain.GitHubSnapshotSource;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubIssueEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubIssueMapper;
import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;
import com.obdeadsoup.devpilot.project.domain.ProjectActivitySourceType;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Issue 快照写入和对应 Activity 的独立短事务执行器。 */
@Service
public class GitHubIssuePersistenceService {
    private final GitHubIssueMapper mapper;
    private final GitHubSnapshotDiffService diffService;
    private final ProjectActivityService activityService;

    public GitHubIssuePersistenceService(GitHubIssueMapper mapper, GitHubSnapshotDiffService diffService,
                                         ProjectActivityService activityService) {
        this.mapper=mapper; this.diffService=diffService; this.activityService=activityService;
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public GitHubIssueApplicationService.UpsertResult insertAndRecord(UpsertGitHubIssueCommand c, String fullName) {
        mapper.insert(c);
        GitHubIssueEntity inserted = required(c);
        record(c, fullName, diffService.issue(null, c));
        return new GitHubIssueApplicationService.UpsertResult(inserted.id(), true, true, false);
    }

    @Transactional(propagation=Propagation.REQUIRES_NEW)
    public GitHubIssueApplicationService.UpsertResult updateAndRecord(GitHubIssueEntity existing,
                                                                       UpsertGitHubIssueCommand c,
                                                                       String fullName) {
        ProjectActivityType type = diffService.issue(existing, c);
        if (mapper.updateSnapshot(existing.id(), existing.version(), c) != 1) {
            GitHubIssueEntity current = required(c);
            if (current.githubUpdatedAt().isAfter(c.githubUpdatedAt())
                    || (current.githubUpdatedAt().equals(c.githubUpdatedAt())
                    && current.contentHash().equals(c.contentHash()))) {
                return new GitHubIssueApplicationService.UpsertResult(current.id(), false, false,
                        current.githubUpdatedAt().isAfter(c.githubUpdatedAt()));
            }
            throw new BusinessException(GitHubSyncErrorCode.SNAPSHOT_STATE_CONFLICT);
        }
        record(c, fullName, type);
        return new GitHubIssueApplicationService.UpsertResult(existing.id(), false, true, false);
    }

    private GitHubIssueEntity required(UpsertGitHubIssueCommand c) {
        return mapper.findByRepositoryAndGitHubId(c.githubRepositoryId(), c.githubIssueId())
                .orElseThrow(() -> new BusinessException(GitHubSyncErrorCode.SNAPSHOT_STATE_CONFLICT));
    }

    private void record(UpsertGitHubIssueCommand c, String fullName, ProjectActivityType type) {
        if (type==null || c.source()==GitHubSnapshotSource.API_BACKFILL
                || (c.source()==GitHubSnapshotSource.WEBHOOK && c.sourceEventId()==null)) return;
        String sourceId=c.sourceEventId()!=null ? c.sourceEventId()
                : "issue:"+c.githubIssueId()+":"+c.contentHash().substring(0,32);
        activityService.recordGitHubActivity(new RecordProjectActivityCommand(c.workspaceId(), c.projectId(),
                c.githubRepositoryId(), fullName, ProjectActivitySourceType.GITHUB, type, sourceId,
                c.authorGitHubUserId(), c.authorLogin(), null, null, null, null, null,
                "Issue #"+c.issueNumber()+": "+safeTitle(c.title()),
                "GitHub Issue #"+c.issueNumber()+" snapshot changed", c.htmlUrl(), c.githubUpdatedAt()));
    }

    private String safeTitle(String value) { return value.length()<=220 ? value : value.substring(0,220); }
}
