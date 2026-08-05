package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.application.port.PullRequestReviewState;
import com.obdeadsoup.devpilot.github.application.port.PullRequestReviewStateReader;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubPullRequestMapper;
import org.springframework.stereotype.Service;
import java.util.Optional;

/** 在 GitHub 模块内核对当前 Head SHA 的 APPROVED Review，避免旧提交审批误代表当前代码。 */
@Service
public class PullRequestReviewStateService implements PullRequestReviewStateReader {
    private final GitHubPullRequestMapper mapper;
    public PullRequestReviewStateService(GitHubPullRequestMapper mapper){this.mapper=mapper;}
    public Optional<PullRequestReviewState> findForTask(long w,long p,long t){return mapper.findReviewStateForTask(w,p,t);}
}
