package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.StartRunRequest;
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
}
