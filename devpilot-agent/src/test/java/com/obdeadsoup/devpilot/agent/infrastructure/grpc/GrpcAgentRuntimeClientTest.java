package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.RunStatus;
import com.obdeadsoup.devpilot.agent.contract.v1.StartRunRequest;
import com.obdeadsoup.devpilot.agent.contract.v1.StartRunResponse;
import io.grpc.Status;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcAgentRuntimeClientTest {

    private final AgentRuntimeGrpc.AgentRuntimeBlockingStub baseStub =
            mock(AgentRuntimeGrpc.AgentRuntimeBlockingStub.class);
    private final AgentRuntimeGrpc.AgentRuntimeBlockingStub deadlineStub =
            mock(AgentRuntimeGrpc.AgentRuntimeBlockingStub.class);
    private GrpcAgentRuntimeClient client;

    @BeforeEach
    void setUp() {
        when(baseStub.withDeadlineAfter(30_000, TimeUnit.MILLISECONDS)).thenReturn(deadlineStub);
        client = new GrpcAgentRuntimeClient(baseStub, Duration.ofSeconds(30));
    }

    @Test
    void mapsInternalCommandToProtoAndSuccessfulResponseBackToCore() {
        when(deadlineStub.startRun(any())).thenReturn(StartRunResponse.newBuilder()
                .setRunId("run-1")
                .setFinalOutput("finished")
                .setStatus(RunStatus.RUN_STATUS_SUCCEEDED)
                .build());

        var result = client.run(new AgentRunCommand("request-1", "run-1", "hello"));

        ArgumentCaptor<StartRunRequest> request = ArgumentCaptor.forClass(StartRunRequest.class);
        verify(deadlineStub).startRun(request.capture());
        assertThat(request.getValue().getRequestId()).isEqualTo("request-1");
        assertThat(request.getValue().getRunId()).isEqualTo("run-1");
        assertThat(request.getValue().getUserInput()).isEqualTo("hello");
        assertThat(result.runId()).isEqualTo("run-1");
        assertThat(result.finalOutput()).isEqualTo("finished");
        assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        verify(baseStub).withDeadlineAfter(30_000, TimeUnit.MILLISECONDS);
    }

    @Test
    void mapsExplicitFailedResponseWithoutLeakingProtoType() {
        when(deadlineStub.startRun(any())).thenReturn(StartRunResponse.newBuilder()
                .setRunId("run-1")
                .setStatus(RunStatus.RUN_STATUS_FAILED)
                .build());

        var result = client.run(new AgentRunCommand("request-1", "run-1", "hello"));

        assertThat(result.status()).isEqualTo(AgentRunStatus.FAILED);
        assertThat(result.getClass().getPackageName())
                .isEqualTo("com.obdeadsoup.devpilot.agent.application");
    }

    @Test
    void rejectsMismatchedRunIdAsProtocolFailure() {
        when(deadlineStub.startRun(any())).thenReturn(StartRunResponse.newBuilder()
                .setRunId("other-run")
                .setStatus(RunStatus.RUN_STATUS_SUCCEEDED)
                .build());

        assertThatThrownBy(() -> client.run(command()))
                .isInstanceOfSatisfying(AgentRuntimeClientException.class, error ->
                        assertThat(error.kind()).isEqualTo(AgentRuntimeFailureKind.PROTOCOL));
    }

    @ParameterizedTest
    @MethodSource("statusMappings")
    void mapsGrpcStatusToStableSanitizedFailure(
            Status status,
            AgentRuntimeFailureKind expected
    ) {
        when(deadlineStub.startRun(any())).thenThrow(
                status.withDescription("private-provider-body").asRuntimeException()
        );

        assertThatThrownBy(() -> client.run(command()))
                .isInstanceOfSatisfying(AgentRuntimeClientException.class, error -> {
                    assertThat(error.kind()).isEqualTo(expected);
                    assertThat(error.getMessage()).doesNotContain("private-provider-body");
                });
    }

    private static Stream<Arguments> statusMappings() {
        return Stream.of(
                Arguments.of(Status.DEADLINE_EXCEEDED, AgentRuntimeFailureKind.DEADLINE_EXCEEDED),
                Arguments.of(Status.UNAVAILABLE, AgentRuntimeFailureKind.UNAVAILABLE),
                Arguments.of(Status.INVALID_ARGUMENT, AgentRuntimeFailureKind.INVALID_ARGUMENT),
                Arguments.of(Status.INTERNAL, AgentRuntimeFailureKind.INTERNAL),
                Arguments.of(Status.UNKNOWN, AgentRuntimeFailureKind.UNKNOWN)
        );
    }

    private AgentRunCommand command() {
        return new AgentRunCommand("request-1", "run-1", "hello");
    }
}
