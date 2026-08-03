package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.domain.GitHubSyncResourceType;

/** 单一 resource_type 的对账 Worker 边界。 */
public interface GitHubReconciliationWorker {
    GitHubSyncResourceType resourceType();
    void reconcile(long runId);
}
