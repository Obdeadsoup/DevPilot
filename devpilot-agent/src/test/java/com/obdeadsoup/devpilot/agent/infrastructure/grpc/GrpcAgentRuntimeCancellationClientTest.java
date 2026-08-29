package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRuntimeCancelCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeCancelStatus;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeCancellationException;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.CancelRunRequest;
import com.obdeadsoup.devpilot.agent.contract.v1.CancelRunResponse;
import com.obdeadsoup.devpilot.agent.contract.v1.CancelRunStatus;
import io.grpc.Status;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GrpcAgentRuntimeCancellationClientTest {
    private final AgentRuntimeGrpc.AgentRuntimeBlockingStub baseStub =
            mock(AgentRuntimeGrpc.AgentRuntimeBlockingStub.class);
    private final AgentRuntimeGrpc.AgentRuntimeBlockingStub deadlineStub =
            mock(AgentRuntimeGrpc.AgentRuntimeBlockingStub.class);
    private GrpcAgentRuntimeCancellationClient client;

    @BeforeEach
    void setUp() {
        when(baseStub.withDeadlineAfter(3_000, TimeUnit.MILLISECONDS)).thenReturn(deadlineStub);
        client = new GrpcAgentRuntimeCancellationClient(baseStub, Duration.ofSeconds(3));
    }

    @Test
    void sendsBothCorrelationIdsAndMapsAccepted() {
        when(deadlineStub.cancelRun(any())).thenReturn(CancelRunResponse.newBuilder()
                .setAccepted(true)
                .setStatus(CancelRunStatus.CANCEL_RUN_STATUS_ACCEPTED)
                .build());

        assertThat(client.cancel(new AgentRuntimeCancelCommand("run-1", "request-1")))
                .isEqualTo(AgentRuntimeCancelStatus.ACCEPTED);
        ArgumentCaptor<CancelRunRequest> request = ArgumentCaptor.forClass(CancelRunRequest.class);
        verify(deadlineStub).cancelRun(request.capture());
        assertThat(request.getValue().getRunId()).isEqualTo("run-1");
        assertThat(request.getValue().getRequestId()).isEqualTo("request-1");
    }

    @Test
    void transportFailureIsSanitized() {
        when(deadlineStub.cancelRun(any())).thenThrow(
                Status.UNAVAILABLE.withDescription("private-body").asRuntimeException());

        assertThatThrownBy(() -> client.cancel(new AgentRuntimeCancelCommand("run-1", "request-1")))
                .isInstanceOf(AgentRuntimeCancellationException.class)
                .hasMessage("Agent Runtime cancel request failed");
    }
}
