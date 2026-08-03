package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncResourceType;
import com.obdeadsoup.devpilot.github.error.GitHubSyncErrorCode;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncRunMapper;
import org.springframework.stereotype.Service;

import java.util.EnumMap;
import java.util.List;
import java.util.Map;

/** 根据持久化 Run 的 resource_type 分发到对应对账 Worker，不信任线程提交时的内存参数。 */
@Service
public class GitHubReconciliationDispatcher {
    private final GitHubSyncRunMapper runMapper;private final Map<GitHubSyncResourceType,GitHubReconciliationWorker> workers;
    public GitHubReconciliationDispatcher(GitHubSyncRunMapper runMapper,List<GitHubReconciliationWorker> workers){
        this.runMapper=runMapper;this.workers=new EnumMap<>(GitHubSyncResourceType.class);
        workers.forEach(worker->this.workers.put(worker.resourceType(),worker));}
    public void dispatch(long runId){var run=runMapper.findById(runId)
            .orElseThrow(()->new BusinessException(GitHubSyncErrorCode.SYNC_RUN_NOT_FOUND));
        GitHubReconciliationWorker worker=workers.get(GitHubSyncResourceType.valueOf(run.resourceType()));
        if(worker==null)throw new BusinessException(GitHubSyncErrorCode.SYNC_TARGET_UNAVAILABLE);worker.reconcile(runId);}
}
