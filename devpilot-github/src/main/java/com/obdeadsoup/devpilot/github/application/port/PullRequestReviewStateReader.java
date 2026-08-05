package com.obdeadsoup.devpilot.github.application.port;

import java.util.Optional;

/** 按 ACTIVE IMPLEMENTED_BY Task Link 暴露 PR 的中立审核状态，不返回 Body 或 GitHub login。 */
public interface PullRequestReviewStateReader {
    Optional<PullRequestReviewState> findForTask(long workspaceId,long projectId,long taskId);
}
