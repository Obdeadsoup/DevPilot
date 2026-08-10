package com.obdeadsoup.devpilot;

import com.obdeadsoup.devpilot.audit.application.AuditQueryService;
import com.obdeadsoup.devpilot.audit.application.OutboxReplayApplicationService;
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
    }
}
