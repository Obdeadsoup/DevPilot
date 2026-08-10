package com.obdeadsoup.devpilot.audit.application;

import com.obdeadsoup.devpilot.audit.event.GitHubSyncReplayCreatedSignal;
import com.obdeadsoup.devpilot.github.application.GitHubReconciliationDispatcher;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** 提交后快速唤醒原 GitHub 状态机；即使 JVM 在此窗口崩溃，数据库扫描仍是可靠恢复来源。 */
@Component
@ConditionalOnProperty(prefix="devpilot.github.reconciliation",name="enabled",havingValue="true")
public class GitHubSyncReplaySignalListener {
    private final TaskExecutor executor;
    private final GitHubReconciliationDispatcher dispatcher;
    public GitHubSyncReplaySignalListener(@Qualifier("githubDeliveryTaskExecutor") TaskExecutor executor,
                                          GitHubReconciliationDispatcher dispatcher) {
        this.executor = executor; this.dispatcher = dispatcher;
    }
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void afterCommit(GitHubSyncReplayCreatedSignal signal) {
        executor.execute(() -> dispatcher.dispatch(signal.runId()));
    }
}
