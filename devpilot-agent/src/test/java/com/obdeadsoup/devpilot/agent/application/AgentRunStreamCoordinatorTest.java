package com.obdeadsoup.devpilot.agent.application;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AgentRunStreamCoordinatorTest {
    private static final LocalDateTime NOW = LocalDateTime.of(2026, 8, 26, 13, 0);
    private final AgentRuntimeStreamingPort streamingPort = mock(AgentRuntimeStreamingPort.class);
    private final AgentRunPersistenceService persistenceService = mock(AgentRunPersistenceService.class);
    private final AgentRunTimeProvider timeProvider = mock(AgentRunTimeProvider.class);
    private final AgentRunEventPublisher publisher = mock(AgentRunEventPublisher.class);
    private AgentRunStreamCoordinator coordinator;
    private AgentRuntimeEventListener listener;

    @BeforeEach
    void setUp() {
        coordinator = new AgentRunStreamCoordinator(
                streamingPort, persistenceService, timeProvider, publisher);
        when(timeProvider.now()).thenReturn(NOW);
        coordinator.start(1, 2, command());
        ArgumentCaptor<AgentRuntimeEventListener> captor =
                ArgumentCaptor.forClass(AgentRuntimeEventListener.class);
        verify(streamingPort).stream(eq(command()), captor.capture());
        listener = captor.getValue();
        verify(publisher).initialize("run-1");
    }

    @Test
    void validSuccessPersistsBeforePublishingTerminal() {
        listener.onEvent(started());
        listener.onEvent(event(2, AgentStreamEventType.MODEL_STEP_STARTED, 1, "", "", ""));
        listener.onEvent(event(3, AgentStreamEventType.RUN_SUCCEEDED, 0, "", "answer", ""));
        listener.onCompleted();

        verify(persistenceService).markSucceeded(1, 2, "run-1", "answer", NOW);
        ArgumentCaptor<AgentStreamEvent> events = ArgumentCaptor.forClass(AgentStreamEvent.class);
        verify(publisher, times(3)).publish(events.capture());
        assertThat(events.getAllValues()).extracting(AgentStreamEvent::sequence)
                .containsExactly(1L, 2L, 3L);
        assertThat(events.getAllValues().getLast().type()).isEqualTo(AgentStreamEventType.RUN_SUCCEEDED);
        verify(persistenceService, never()).markFailed(anyLong(), anyLong(), any(), any(), any());
    }

    @Test
    void explicitRemoteFailureUsesStableProjectionAndKeepsRuntimeKindForSse() {
        listener.onEvent(started());
        listener.onEvent(event(2, AgentStreamEventType.RUN_FAILED, 0, "", "", "MODEL_ERROR"));

        verify(persistenceService).markFailed(1, 2, "run-1", AgentRunFailureKind.REMOTE_FAILED, NOW);
        verify(publisher).publish(event(2, AgentStreamEventType.RUN_FAILED, 0, "", "", "MODEL_ERROR"));
    }

    @Test
    void runIdMismatchBecomesProtocolFailure() {
        assertProtocolFailure(new AgentStreamEvent(
                "other:1", "other", 1, AgentStreamEventType.RUN_STARTED, 0, "", "", ""));
    }

    @Test
    void duplicateSequenceBecomesProtocolFailure() {
        listener.onEvent(started());
        listener.onEvent(event(1, AgentStreamEventType.MODEL_STEP_STARTED, 1, "", "", ""));
        verify(persistenceService).markFailed(
                1, 2, "run-1", AgentRunFailureKind.PROTOCOL, NOW);
    }

    @Test
    void sequenceGapBecomesProtocolFailure() {
        listener.onEvent(started());
        listener.onEvent(event(3, AgentStreamEventType.MODEL_STEP_STARTED, 1, "", "", ""));
        verify(persistenceService).markFailed(
                1, 2, "run-1", AgentRunFailureKind.PROTOCOL, NOW);
    }

    @Test
    void mismatchedEventIdBecomesProtocolFailure() {
        listener.onEvent(new AgentStreamEvent(
                "run-1:99", "run-1", 1, AgentStreamEventType.RUN_STARTED, 0, "", "", ""));
        verify(persistenceService).markFailed(
                1, 2, "run-1", AgentRunFailureKind.PROTOCOL, NOW);
    }

    @Test
    void completedWithoutTerminalPublishesSyntheticProtocolTerminal() {
        listener.onEvent(started());
        listener.onCompleted();

        verify(persistenceService).markFailed(1, 2, "run-1", AgentRunFailureKind.PROTOCOL, NOW);
        verify(publisher).publish(event(
                2, AgentStreamEventType.RUN_FAILED, 0, "", "", "PROTOCOL"));
    }

    @Test
    void unavailablePublishesSyntheticFailure() {
        listener.onError(AgentRuntimeStreamFailureKind.UNAVAILABLE);
        verify(persistenceService).markFailed(1, 2, "run-1", AgentRunFailureKind.UNAVAILABLE, NOW);
        verify(publisher).publish(event(
                1, AgentStreamEventType.RUN_FAILED, 0, "", "", "UNAVAILABLE"));
    }

    @Test
    void deadlinePublishesSyntheticFailure() {
        listener.onError(AgentRuntimeStreamFailureKind.DEADLINE_EXCEEDED);
        verify(persistenceService).markFailed(
                1, 2, "run-1", AgentRunFailureKind.DEADLINE_EXCEEDED, NOW);
    }

    @Test
    void secondTerminalAndErrorAfterTerminalCannotOverwriteSucceededProjection() {
        listener.onEvent(started());
        listener.onEvent(event(2, AgentStreamEventType.RUN_SUCCEEDED, 0, "", "answer", ""));
        listener.onEvent(event(3, AgentStreamEventType.RUN_FAILED, 0, "", "", "INTERNAL"));
        listener.onError(AgentRuntimeStreamFailureKind.UNAVAILABLE);

        verify(persistenceService).markSucceeded(1, 2, "run-1", "answer", NOW);
        verify(persistenceService, never()).markFailed(anyLong(), anyLong(), any(), any(), any());
        verify(publisher, times(2)).publish(any());
    }

    @Test
    void nonTerminalEventAfterTerminalIsRejectedWithoutASecondProjection() {
        listener.onEvent(started());
        listener.onEvent(event(2, AgentStreamEventType.RUN_SUCCEEDED, 0, "", "answer", ""));
        listener.onEvent(event(3, AgentStreamEventType.MODEL_STEP_STARTED, 2, "", "", ""));

        verify(persistenceService).markSucceeded(1, 2, "run-1", "answer", NOW);
        verify(persistenceService, never()).markFailed(anyLong(), anyLong(), any(), any(), any());
        verify(publisher, times(2)).publish(any());
    }

    private void assertProtocolFailure(AgentStreamEvent invalid) {
        listener.onEvent(invalid);
        verify(persistenceService).markFailed(1, 2, "run-1", AgentRunFailureKind.PROTOCOL, NOW);
    }

    private AgentStreamEvent started() {
        return event(1, AgentStreamEventType.RUN_STARTED, 0, "", "", "");
    }

    private AgentStreamEvent event(long sequence, AgentStreamEventType type, int step,
                                   String toolName, String output, String failure) {
        return new AgentStreamEvent("run-1:" + sequence, "run-1", sequence, type,
                step, toolName, output, failure);
    }

    private AgentRunCommand command() {
        return new AgentRunCommand("request-1", "run-1", "hello");
    }
}
