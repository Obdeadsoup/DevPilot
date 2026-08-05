package com.obdeadsoup.devpilot;

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

    @Test
    void contextLoads() {
        assertThat(taskApplicationService).isNotNull();
        assertThat(taskWorkflowService).isNotNull();
        assertThat(taskGitHubLinkService).isNotNull();
        assertThat(notificationApplicationService).isNotNull();
        assertThat(notificationQueryService).isNotNull();
        assertThat(notificationReminderScanService).isNotNull();
    }
}
