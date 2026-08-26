package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeEventListener;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamFailureKind;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEventType;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentEvent;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentEventType;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.StreamRunRequest;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcAgentRuntimeStreamingClientTest {
    private final AgentRuntimeGrpc.AgentRuntimeStub baseStub =
            mock(AgentRuntimeGrpc.AgentRuntimeStub.class);
    private final AgentRuntimeGrpc.AgentRuntimeStub deadlineStub =
            mock(AgentRuntimeGrpc.AgentRuntimeStub.class);
    private final AgentRuntimeEventListener listener = mock(AgentRuntimeEventListener.class);
    private GrpcAgentRuntimeStreamingClient client;

    @BeforeEach
    void setUp() {
        when(baseStub.withDeadlineAfter(600_000, TimeUnit.MILLISECONDS)).thenReturn(deadlineStub);
        client = new GrpcAgentRuntimeStreamingClient(baseStub, Duration.ofMinutes(10));
    }

    @Test
    @SuppressWarnings("unchecked")
    void mapsRequestEventAndCompletionWithoutLeakingProtoToCore() {
        client.stream(command(), listener);

        ArgumentCaptor<StreamRunRequest> request = ArgumentCaptor.forClass(StreamRunRequest.class);
        ArgumentCaptor<StreamObserver<AgentEvent>> observer = ArgumentCaptor.forClass(StreamObserver.class);
        verify(deadlineStub).streamRun(request.capture(), observer.capture());
        assertThat(request.getValue().getRunId()).isEqualTo("run-1");
        assertThat(request.getValue().getRequestId()).isEqualTo("request-1");
        assertThat(request.getValue().getUserInput()).isEqualTo("hello");

        observer.getValue().onNext(event(1, AgentEventType.AGENT_EVENT_TYPE_RUN_STARTED));
        observer.getValue().onCompleted();

        var eventCaptor = ArgumentCaptor.forClass(com.obdeadsoup.devpilot.agent.application.AgentStreamEvent.class);
        verify(listener).onEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().type()).isEqualTo(AgentStreamEventType.RUN_STARTED);
        assertThat(eventCaptor.getValue().getClass().getPackageName())
                .isEqualTo("com.obdeadsoup.devpilot.agent.application");
        verify(listener).onCompleted();
        verify(baseStub).withDeadlineAfter(600_000, TimeUnit.MILLISECONDS);
    }

    @ParameterizedTest
    @MethodSource("statusMappings")
    @SuppressWarnings("unchecked")
    void mapsTransportErrorsToStableKinds(Status status, AgentRuntimeStreamFailureKind expected) {
        client.stream(command(), listener);
        ArgumentCaptor<StreamObserver<AgentEvent>> observer = ArgumentCaptor.forClass(StreamObserver.class);
        verify(deadlineStub).streamRun(any(), observer.capture());

        observer.getValue().onError(status.withDescription("private-body").asRuntimeException());

        verify(listener).onError(expected);
    }

    @Test
    @SuppressWarnings("unchecked")
    void unknownEventTypeBecomesProtocolFailureOnce() {
        client.stream(command(), listener);
        ArgumentCaptor<StreamObserver<AgentEvent>> observer = ArgumentCaptor.forClass(StreamObserver.class);
        verify(deadlineStub).streamRun(any(), observer.capture());

        observer.getValue().onNext(event(1, AgentEventType.AGENT_EVENT_TYPE_UNSPECIFIED));
        observer.getValue().onCompleted();

        verify(listener).onError(AgentRuntimeStreamFailureKind.PROTOCOL);
        verify(listener, org.mockito.Mockito.never()).onCompleted();
    }

    private static Stream<Arguments> statusMappings() {
        return Stream.of(
                Arguments.of(Status.DEADLINE_EXCEEDED, AgentRuntimeStreamFailureKind.DEADLINE_EXCEEDED),
                Arguments.of(Status.UNAVAILABLE, AgentRuntimeStreamFailureKind.UNAVAILABLE),
                Arguments.of(Status.INVALID_ARGUMENT, AgentRuntimeStreamFailureKind.INVALID_ARGUMENT),
                Arguments.of(Status.INTERNAL, AgentRuntimeStreamFailureKind.INTERNAL),
                Arguments.of(Status.UNKNOWN, AgentRuntimeStreamFailureKind.UNKNOWN)
        );
    }

    private AgentEvent event(long sequence, AgentEventType type) {
        return AgentEvent.newBuilder()
                .setRunId("run-1")
                .setEventId("run-1:" + sequence)
                .setSequence(sequence)
                .setType(type)
                .build();
    }

    private AgentRunCommand command() {
        return new AgentRunCommand("request-1", "run-1", "hello");
    }
}
