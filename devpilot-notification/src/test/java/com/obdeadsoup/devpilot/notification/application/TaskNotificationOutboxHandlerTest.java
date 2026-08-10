package com.obdeadsoup.devpilot.notification.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.notification.domain.NotificationDedupeKeyFactory;
import com.obdeadsoup.devpilot.notification.domain.NotificationType;
import com.obdeadsoup.devpilot.outbox.domain.OutboxEventEnvelope;
import com.obdeadsoup.devpilot.project.application.port.ProjectNotificationRecipientQuery;
import com.obdeadsoup.devpilot.task.application.outbox.TaskInstantEventType;
import com.obdeadsoup.devpilot.task.application.outbox.TaskInstantNotificationPayloadV1;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskNotificationOutboxHandlerTest {

    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private final ProjectNotificationRecipientQuery recipients = mock(ProjectNotificationRecipientQuery.class);
    private final NotificationApplicationService notifications = mock(NotificationApplicationService.class);
    private final NotificationDedupeKeyFactory dedupeKeys = new NotificationDedupeKeyFactory();

    @Test
    void assignedTargetsAssigneeWithStableDedupeKey() {
        handle(TaskInstantEventType.TASK_ASSIGNED_V1, payload(20L, null, 10L));

        CreateNotificationCommand command = capturedCommands().getFirst();
        assertThat(command.recipientUserId()).isEqualTo(20);
        assertThat(command.type()).isEqualTo(NotificationType.TASK_ASSIGNED);
        assertThat(command.dedupeKey()).isEqualTo("task:103:v7:assigned");
    }

    @Test
    void submittedForReviewReusesManagerRecipientQuery() {
        when(recipients.findManagerUserIds(2, 3)).thenReturn(Set.of(30L, 31L));
        handle(TaskInstantEventType.TASK_SUBMITTED_FOR_REVIEW_V1, payload(20L, null, 10L));

        verify(recipients).findManagerUserIds(2, 3);
        assertThat(capturedCommands()).extracting(CreateNotificationCommand::recipientUserId)
                .containsExactlyInAnyOrder(30L, 31L);
    }

    @Test
    void completedDeduplicatesReporterAndAssignee() {
        handle(TaskInstantEventType.TASK_COMPLETED_V1, payload(10L, null, 10L));

        assertThat(capturedCommands()).hasSize(1);
        assertThat(capturedCommands().getFirst().type()).isEqualTo(NotificationType.TASK_COMPLETED);
    }

    private void handle(TaskInstantEventType type, TaskInstantNotificationPayloadV1 payload) {
        TaskNotificationOutboxHandler handler = new TaskNotificationOutboxHandler(
                type, objectMapper, recipients, notifications, dedupeKeys);
        handler.handle(new OutboxEventEnvelope(
                "task:103:v7:" + type.eventKeySuffix(), "TASK", 103, type.name(), 1,
                objectMapper.valueToTree(payload), payload.occurredAt()));
    }

    private List<CreateNotificationCommand> capturedCommands() {
        ArgumentCaptor<CreateNotificationCommand> captor = ArgumentCaptor.forClass(CreateNotificationCommand.class);
        verify(notifications, org.mockito.Mockito.atLeastOnce()).createIfAbsent(captor.capture());
        return captor.getAllValues();
    }

    private TaskInstantNotificationPayloadV1 payload(Long assignee, Long previous, Long reporter) {
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 10, 12, 0);
        return new TaskInstantNotificationPayloadV1(
                2, 3, 103, 7, "DP-103", "Review", 11, occurredAt,
                assignee, previous, reporter);
    }
}
