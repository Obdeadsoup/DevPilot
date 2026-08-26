package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentEvent;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentEventType;
import com.obdeadsoup.devpilot.agent.contract.v1.StartRunRequest;
import com.obdeadsoup.devpilot.agent.contract.v1.StreamRunRequest;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class GeneratedContractTest {

    @Test
    void generatedContractContainsV1StartRunFieldsAndMethod() {
        var request = StartRunRequest.newBuilder()
                .setRequestId("request-1")
                .setRunId("run-1")
                .setUserInput("hello")
                .build();

        assertThat(request.getRequestId()).isEqualTo("request-1");
        assertThat(request.getRunId()).isEqualTo("run-1");
        assertThat(request.getUserInput()).isEqualTo("hello");
        assertThat(AgentRuntimeGrpc.getStartRunMethod().getFullMethodName())
                .isEqualTo("devpilot.agent.v1.AgentRuntime/StartRun");
    }

    @Test
    void generatedContractContainsStreamingIdentitySequenceAndTypedLifecycle() {
        var request = StreamRunRequest.newBuilder()
                .setRunId("run-1")
                .setRequestId("request-1")
                .setUserInput("hello")
                .build();
        var event = AgentEvent.newBuilder()
                .setEventId("run-1:1")
                .setRunId("run-1")
                .setSequence(1)
                .setType(AgentEventType.AGENT_EVENT_TYPE_RUN_STARTED)
                .build();

        assertThat(request.getRequestId()).isEqualTo("request-1");
        assertThat(request.getUserInput()).isEqualTo("hello");
        assertThat(event.getSequence()).isEqualTo(1);
        assertThat(event.getType()).isEqualTo(AgentEventType.AGENT_EVENT_TYPE_RUN_STARTED);
        assertThat(AgentRuntimeGrpc.getStreamRunMethod().getFullMethodName())
                .isEqualTo("devpilot.agent.v1.AgentRuntime/StreamRun");
    }
}
