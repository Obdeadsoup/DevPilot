package com.obdeadsoup.devpilot.notification.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.notification.application.NotificationApplicationService;
import com.obdeadsoup.devpilot.notification.application.TaskNotificationOutboxHandler;
import com.obdeadsoup.devpilot.notification.application.NotificationMetrics;
import com.obdeadsoup.devpilot.notification.domain.NotificationDedupeKeyFactory;
import com.obdeadsoup.devpilot.outbox.domain.OutboxEventHandler;
import com.obdeadsoup.devpilot.project.application.port.ProjectNotificationRecipientQuery;
import com.obdeadsoup.devpilot.task.application.outbox.TaskInstantEventType;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 为六种 Task V1 事件注册唯一的白名单 Handler，不使用反射或 Payload 中的类名。 */
@Configuration(proxyBeanMethods = false)
public class TaskNotificationOutboxConfiguration {
    private final NotificationMetrics metrics;

    public TaskNotificationOutboxConfiguration(NotificationMetrics metrics) {
        this.metrics = metrics;
    }

    @Bean
    OutboxEventHandler taskAssignedOutboxHandler(ObjectMapper mapper, ProjectNotificationRecipientQuery recipients,
                                                  NotificationApplicationService notifications, NotificationDedupeKeyFactory keys) {
        return handler(TaskInstantEventType.TASK_ASSIGNED_V1, mapper, recipients, notifications, keys);
    }

    @Bean
    OutboxEventHandler taskUnassignedOutboxHandler(ObjectMapper mapper, ProjectNotificationRecipientQuery recipients,
                                                    NotificationApplicationService notifications, NotificationDedupeKeyFactory keys) {
        return handler(TaskInstantEventType.TASK_UNASSIGNED_V1, mapper, recipients, notifications, keys);
    }

    @Bean
    OutboxEventHandler taskSubmittedReviewOutboxHandler(ObjectMapper mapper, ProjectNotificationRecipientQuery recipients,
                                                         NotificationApplicationService notifications, NotificationDedupeKeyFactory keys) {
        return handler(TaskInstantEventType.TASK_SUBMITTED_FOR_REVIEW_V1, mapper, recipients, notifications, keys);
    }

    @Bean
    OutboxEventHandler taskChangesRequestedOutboxHandler(ObjectMapper mapper, ProjectNotificationRecipientQuery recipients,
                                                          NotificationApplicationService notifications, NotificationDedupeKeyFactory keys) {
        return handler(TaskInstantEventType.TASK_CHANGES_REQUESTED_V1, mapper, recipients, notifications, keys);
    }

    @Bean
    OutboxEventHandler taskCompletedOutboxHandler(ObjectMapper mapper, ProjectNotificationRecipientQuery recipients,
                                                   NotificationApplicationService notifications, NotificationDedupeKeyFactory keys) {
        return handler(TaskInstantEventType.TASK_COMPLETED_V1, mapper, recipients, notifications, keys);
    }

    @Bean
    OutboxEventHandler taskReopenedOutboxHandler(ObjectMapper mapper, ProjectNotificationRecipientQuery recipients,
                                                  NotificationApplicationService notifications, NotificationDedupeKeyFactory keys) {
        return handler(TaskInstantEventType.TASK_REOPENED_V1, mapper, recipients, notifications, keys);
    }

    private OutboxEventHandler handler(
            TaskInstantEventType type,
            ObjectMapper mapper,
            ProjectNotificationRecipientQuery recipients,
            NotificationApplicationService notifications,
            NotificationDedupeKeyFactory keys) {
        return new TaskNotificationOutboxHandler(type, mapper, recipients, notifications, keys, metrics);
    }
}
