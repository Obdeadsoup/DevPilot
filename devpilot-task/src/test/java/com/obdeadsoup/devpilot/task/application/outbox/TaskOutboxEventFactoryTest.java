package com.obdeadsoup.devpilot.task.application.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.outbox.application.OutboxEventPublisher;
import com.obdeadsoup.devpilot.outbox.domain.OutboxEventEnvelope;
import com.obdeadsoup.devpilot.task.persistence.entity.TaskEntity;
import java.time.LocalDateTime;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class TaskOutboxEventFactoryTest {

    @Test
    void publishesDeterministicVersionedAndMinimalAssignedEvent() {
        OutboxEventPublisher publisher = mock(OutboxEventPublisher.class);
        when(publisher.publish(any())).thenReturn(99L);
        TaskOutboxEventFactory factory = new TaskOutboxEventFactory(publisher, new ObjectMapper().findAndRegisterModules());
        TaskEntity task = task();
        LocalDateTime occurredAt = LocalDateTime.of(2026, 8, 10, 12, 0);

        factory.publishAssigned(task, "DP", 7, 11, 22, occurredAt);

        ArgumentCaptor<OutboxEventEnvelope> captor = ArgumentCaptor.forClass(OutboxEventEnvelope.class);
        verify(publisher).publish(captor.capture());
        OutboxEventEnvelope event = captor.getValue();
        assertThat(event.eventKey()).isEqualTo("task:103:v7:assigned");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.payload().path("assigneeUserId").asLong()).isEqualTo(22);
        assertThat(event.payload().path("safeTitleSnapshot").asText()).isEqualTo("Review fix");
        assertThat(event.payload().has("description")).isFalse();
        assertThat(event.payload().toString()).doesNotContain("token", "secret", "@class", "private body");
        assertThat(factory.eventKey(103, 7, TaskInstantEventType.TASK_ASSIGNED_V1))
                .isEqualTo(event.eventKey());
    }

    private TaskEntity task() {
        TaskEntity task = new TaskEntity();
        task.setId(103L);
        task.setWorkspaceId(2);
        task.setProjectId(3);
        task.setTitle("Review\u0000fix");
        task.setDescription("private body");
        task.setReporterUserId(11);
        return task;
    }
}
