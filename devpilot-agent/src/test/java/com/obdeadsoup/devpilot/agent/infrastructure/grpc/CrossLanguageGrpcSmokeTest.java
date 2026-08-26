package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeEventListener;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamFailureKind;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEvent;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEventType;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 必须连接独立 Python 进程；普通单测默认跳过，不能用 Java in-process Server 冒充。 */
@EnabledIfEnvironmentVariable(
        named = "DEVPILOT_AGENT_CROSS_LANGUAGE_SMOKE",
        matches = "true"
)
class CrossLanguageGrpcSmokeTest {

    @Test
    void javaProcessCallsPythonFakeAgentOverTcpHttp2() throws InterruptedException {
        String host = System.getenv().getOrDefault("AGENT_GRPC_CLIENT_HOST", "127.0.0.1");
        int port = Integer.parseInt(System.getenv().getOrDefault("AGENT_GRPC_PORT", "50051"));
        AgentGrpcProperties properties = new AgentGrpcProperties(
                host,
                port,
                Duration.ofSeconds(10),
                Duration.ofSeconds(30),
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

            List<AgentStreamEvent> events = new CopyOnWriteArrayList<>();
            CountDownLatch completed = new CountDownLatch(1);
            var streamingClient = new GrpcAgentRuntimeStreamingClient(
                    AgentRuntimeGrpc.newStub(channel.channel()), properties.streamDeadline());
            streamingClient.stream(new AgentRunCommand(
                    "p0-06-java-request", "p0-06-cross-language-run", "hello-streaming"
            ), new AgentRuntimeEventListener() {
                @Override
                public void onEvent(AgentStreamEvent event) {
                    events.add(event);
                }

                @Override
                public void onError(AgentRuntimeStreamFailureKind failureKind) {
                    completed.countDown();
                }

                @Override
                public void onCompleted() {
                    completed.countDown();
                }
            });

            assertThat(completed.await(10, TimeUnit.SECONDS)).isTrue();
            assertThat(events).extracting(AgentStreamEvent::type).containsExactly(
                    AgentStreamEventType.RUN_STARTED,
                    AgentStreamEventType.MODEL_STEP_STARTED,
                    AgentStreamEventType.RUN_SUCCEEDED);
            assertThat(events).extracting(AgentStreamEvent::sequence).containsExactly(1L, 2L, 3L);
            assertThat(events).extracting(AgentStreamEvent::eventId).containsExactly(
                    "p0-06-cross-language-run:1",
                    "p0-06-cross-language-run:2",
                    "p0-06-cross-language-run:3");
            assertThat(events.getLast().finalOutput()).isEqualTo("fake:hello-streaming");
        }
    }
}
