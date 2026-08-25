package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

/** 必须连接独立 Python 进程；普通单测默认跳过，不能用 Java in-process Server 冒充。 */
@EnabledIfEnvironmentVariable(
        named = "DEVPILOT_AGENT_CROSS_LANGUAGE_SMOKE",
        matches = "true"
)
class CrossLanguageGrpcSmokeTest {

    @Test
    void javaProcessCallsPythonFakeAgentOverTcpHttp2() {
        String host = System.getenv().getOrDefault("AGENT_GRPC_CLIENT_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("AGENT_GRPC_PORT", "50051"));
        AgentGrpcProperties properties = new AgentGrpcProperties(
                host,
                port,
                Duration.ofSeconds(10),
                true
        );

        try (AgentGrpcChannel channel = AgentGrpcChannel.open(properties)) {
            var client = new GrpcAgentRuntimeClient(
                    AgentRuntimeGrpc.newBlockingStub(channel.channel()),
                    properties.deadline()
            );

            var result = client.run(new AgentRunCommand(
                    "p0-04-java-request",
                    "p0-04-cross-language-run",
                    "hello-cross-language"
            ));

            assertThat(result.runId()).isEqualTo("p0-04-cross-language-run");
            assertThat(result.finalOutput()).isEqualTo("fake:hello-cross-language");
            assertThat(result.status()).isEqualTo(AgentRunStatus.SUCCEEDED);
        }
    }
}
