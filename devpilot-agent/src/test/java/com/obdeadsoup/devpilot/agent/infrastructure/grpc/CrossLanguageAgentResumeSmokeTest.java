package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.contract.v1.AgentEvent;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentEventType;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import com.obdeadsoup.devpilot.agent.contract.v1.CancelRunRequest;
import com.obdeadsoup.devpilot.agent.contract.v1.CancelRunStatus;
import com.obdeadsoup.devpilot.agent.contract.v1.ResumeRunRequest;
import com.obdeadsoup.devpilot.agent.contract.v1.StartRunRequest;
import io.grpc.ConnectivityState;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@EnabledIfEnvironmentVariable(named = "DEVPILOT_AGENT_RESILIENCE_SMOKE", matches = "true")
class CrossLanguageAgentResumeSmokeTest {
    @TempDir
    Path runtimeDirectory;

    @Test
    void javaResumesPythonCheckpointWithoutResubmittingInput() throws Exception {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("agent-service"))) {
            root = root.getParent();
        }
        assertThat(root).isNotNull();
        int port;
        try (ServerSocket probe = new ServerSocket(0)) {
            port = probe.getLocalPort();
        }
        Path configured = Path.of(System.getenv().getOrDefault(
                "DEVPILOT_AGENT_PYTHON", ".venv/Scripts/python.exe"));
        Path python = configured.isAbsolute() ? configured : root.resolve(configured);
        ProcessBuilder builder = new ProcessBuilder(python.toString(),
                root.resolve("agent-service/tests/cross_language_resume_server.py").toString());
        builder.directory(root.toFile());
        builder.environment().put("PYTHONPATH", root.resolve("agent-service/src").toString());
        builder.environment().put("AGENT_GRPC_PORT", Integer.toString(port));
        builder.environment().put("AGENT_RUNTIME_DB_PATH",
                runtimeDirectory.resolve("runtime.sqlite3").toString());
        builder.redirectErrorStream(true);
        Process process = builder.start();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port)
                .usePlaintext().proxyDetector(address -> null).disableRetry().build();
        try {
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
            while (channel.getState(true) != ConnectivityState.READY && System.nanoTime() < deadline) {
                assertThat(process.isAlive()).isTrue();
                Thread.sleep(50);
            }
            assertThat(channel.getState(false)).isEqualTo(ConnectivityState.READY);
            var stub = AgentRuntimeGrpc.newBlockingStub(channel).withDeadlineAfter(10, TimeUnit.SECONDS);
            assertThatThrownBy(() -> stub.startRun(StartRunRequest.newBuilder()
                    .setRunId("run").setRequestId("request").setUserInput("original-input").build()))
                    .isInstanceOfSatisfying(StatusRuntimeException.class,
                            error -> assertThat(error.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL));
            List<AgentEvent> events = new ArrayList<>();
            stub.resumeRun(ResumeRunRequest.newBuilder().setRunId("run").setRequestId("request").build())
                    .forEachRemaining(events::add);
            assertThat(events).extracting(AgentEvent::getType).containsExactly(
                    AgentEventType.AGENT_EVENT_TYPE_RUN_STARTED,
                    AgentEventType.AGENT_EVENT_TYPE_MODEL_STEP_STARTED,
                    AgentEventType.AGENT_EVENT_TYPE_RUN_SUCCEEDED);
            assertThat(events.get(1).getStep()).isEqualTo(2);
            assertThat(events.getLast().getFinalOutput()).isEqualTo("resumed:original-input");
            var terminal = stub.cancelRun(CancelRunRequest.newBuilder()
                    .setRunId("run").setRequestId("request").build());
            assertThat(terminal.getStatus()).isEqualTo(CancelRunStatus.CANCEL_RUN_STATUS_ALREADY_TERMINAL);
            assertThat(terminal.getRuntimeStatus()).isEqualTo("SUCCEEDED");
        } finally {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            process.destroy();
            if (!process.waitFor(3, TimeUnit.SECONDS)) {
                process.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
            }
        }
    }
}
