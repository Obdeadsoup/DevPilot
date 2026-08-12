package com.obdeadsoup.devpilot;

import com.obdeadsoup.devpilot.audit.application.AuditQueryService;
import com.obdeadsoup.devpilot.audit.application.OutboxReplayApplicationService;
import com.obdeadsoup.devpilot.github.application.GitHubBacklogSnapshotService;
import com.obdeadsoup.devpilot.github.application.GitHubBacklogRefreshScheduler;
import com.obdeadsoup.devpilot.outbox.application.OutboxBacklogSnapshotService;
import com.obdeadsoup.devpilot.outbox.application.OutboxBacklogRefreshScheduler;
import com.obdeadsoup.devpilot.framework.correlation.CorrelationIdFilter;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.beans.factory.annotation.Autowired;
import com.obdeadsoup.devpilot.task.application.TaskApplicationService;
import com.obdeadsoup.devpilot.task.application.TaskGitHubLinkService;
import com.obdeadsoup.devpilot.task.application.TaskWorkflowService;
import com.obdeadsoup.devpilot.notification.application.NotificationApplicationService;
import com.obdeadsoup.devpilot.notification.application.NotificationQueryService;
import com.obdeadsoup.devpilot.notification.application.NotificationReminderScanService;
import com.obdeadsoup.devpilot.outbox.application.OutboxEventPublisher;
import com.obdeadsoup.devpilot.outbox.application.OutboxWorker;
import com.obdeadsoup.devpilot.notification.sse.NotificationSseRegistry;
import static org.assertj.core.api.Assertions.assertThat;

@ActiveProfiles("test")
@SpringBootTest
@Import(IsolatedPersistenceTestConfiguration.class)
class DevPilotApplicationTests {
    @Autowired private TaskApplicationService taskApplicationService;
    @Autowired private TaskWorkflowService taskWorkflowService;
    @Autowired private TaskGitHubLinkService taskGitHubLinkService;
    @Autowired private NotificationApplicationService notificationApplicationService;
    @Autowired private NotificationQueryService notificationQueryService;
    @Autowired private NotificationReminderScanService notificationReminderScanService;
    @Autowired private OutboxEventPublisher outboxEventPublisher;
    @Autowired private OutboxWorker outboxWorker;
    @Autowired private NotificationSseRegistry notificationSseRegistry;
    @Autowired private AuditQueryService auditQueryService;
    @Autowired private OutboxReplayApplicationService outboxReplayApplicationService;
    @Autowired private GitHubBacklogSnapshotService gitHubBacklogSnapshotService;
    @Autowired private OutboxBacklogSnapshotService outboxBacklogSnapshotService;
    @Autowired private CorrelationIdFilter correlationIdFilter;
    @Autowired(required = false) private GitHubBacklogRefreshScheduler gitHubBacklogRefreshScheduler;
    @Autowired(required = false) private OutboxBacklogRefreshScheduler outboxBacklogRefreshScheduler;

    @Test
    void contextLoads() {
        assertThat(taskApplicationService).isNotNull();
        assertThat(taskWorkflowService).isNotNull();
        assertThat(taskGitHubLinkService).isNotNull();
        assertThat(notificationApplicationService).isNotNull();
        assertThat(notificationQueryService).isNotNull();
        assertThat(notificationReminderScanService).isNotNull();
        assertThat(outboxEventPublisher).isNotNull();
        assertThat(outboxWorker).isNotNull();
        assertThat(notificationSseRegistry).isNotNull();
        assertThat(auditQueryService).isNotNull();
        assertThat(outboxReplayApplicationService).isNotNull();
        assertThat(gitHubBacklogSnapshotService).isNotNull();
        assertThat(outboxBacklogSnapshotService).isNotNull();
        assertThat(correlationIdFilter).isNotNull();
        assertThat(gitHubBacklogRefreshScheduler).isNull();
        assertThat(outboxBacklogRefreshScheduler).isNull();
    }
}
