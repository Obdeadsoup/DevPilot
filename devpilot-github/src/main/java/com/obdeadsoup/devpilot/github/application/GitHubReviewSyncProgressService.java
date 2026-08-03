package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubPullRequestEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 仅在一个 PR 的全部 Review 页落库后推进 PR 级 Review 水位。 */
@Service
public class GitHubReviewSyncProgressService{
    private final GitHubPullRequestMapper mapper;public GitHubReviewSyncProgressService(GitHubPullRequestMapper mapper){this.mapper=mapper;}
    @Transactional public void complete(GitHubPullRequestEntity pr,LocalDateTime syncedAt){
        if(mapper.markReviewsSynced(pr.id(),pr.version(),syncedAt)!=1)
            throw new BusinessException(GitHubSyncErrorCode.SNAPSHOT_STATE_CONFLICT);}
}
