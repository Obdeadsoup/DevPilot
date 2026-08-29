package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeCancelCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeCancelStatus;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeEventListener;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamFailureKind;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEvent;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEventType;
import com.obdeadsoup.devpilot.agent.contract.v1.AgentRuntimeGrpc;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.grpc.ManagedChannel;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.io.IOException;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 TCP/HTTP2 fault injection；显式环境开关避免普通单测依赖本机 Python。 */
@EnabledIfEnvironmentVariable(named = "DEVPILOT_AGENT_RESILIENCE_SMOKE", matches = "true")
class CrossLanguageAgentResilienceSmokeTest {

    @Test
    void downOpenRecoverCancelAndCapacity() throws Exception {
        int port = freePort();
        ManagedChannel channel = NettyChannelBuilder.forAddress("127.0.0.1", port)
                .usePlaintext()
                .proxyDetector(targetServerAddress -> null)
                .disableRetry()
                .build();
        Process python = null;
        try {
            AgentRuntimeGrpc.AgentRuntimeStub asyncStub = AgentRuntimeGrpc.newStub(channel);
            GrpcAgentRuntimeStreamingClient raw = new GrpcAgentRuntimeStreamingClient(
                    asyncStub, Duration.ofSeconds(2));
            CircuitBreaker circuit = CircuitBreaker.of("fault-injection", CircuitBreakerConfig.custom()
                    .slidingWindowSize(2).minimumNumberOfCalls(2).failureRateThreshold(50)
                    .waitDurationInOpenState(Duration.ofMillis(300))
                    .permittedNumberOfCallsInHalfOpenState(1).build());
            var resilient = resilient(raw, circuit, 4);

            assertThat(run(resilient, "down-1").failure).isEqualTo(AgentRuntimeStreamFailureKind.UNAVAILABLE);
            assertThat(run(resilient, "down-2").failure).isEqualTo(AgentRuntimeStreamFailureKind.UNAVAILABLE);
            assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.OPEN);
            assertThat(run(resilient, "open-fast-fail").failure)
                    .isEqualTo(AgentRuntimeStreamFailureKind.CIRCUIT_OPEN);

            python = startPython(port);
            channel.resetConnectBackoff();
            long readyDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (channel.getState(true) != io.grpc.ConnectivityState.READY
                    && System.nanoTime() < readyDeadline) {
                if (!python.isAlive()) {
                    throw new IOException("Python process exited: "
                            + new String(python.getInputStream().readAllBytes()));
                }
                Thread.sleep(50);
            }
            assertThat(channel.getState(false)).isEqualTo(io.grpc.ConnectivityState.READY);
            Thread.sleep(350);
            RecordingListener recovered = run(resilient, "recovered");
            assertThat(recovered.terminal).isEqualTo(AgentStreamEventType.RUN_SUCCEEDED);
            assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

            GrpcAgentRuntimeCancellationClient cancellation = new GrpcAgentRuntimeCancellationClient(
                    AgentRuntimeGrpc.newBlockingStub(channel), Duration.ofSeconds(2));
            RecordingListener cancellable = new RecordingListener();
            raw.stream(command("cancel-me"), cancellable);
            assertThat(cancellable.started.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellation.cancel(new AgentRuntimeCancelCommand(
                    "cancel-me", "request-cancel-me"))).isEqualTo(AgentRuntimeCancelStatus.ACCEPTED);
            assertThat(cancellable.finished.await(3, TimeUnit.SECONDS)).isTrue();
            assertThat(cancellable.terminal).isEqualTo(AgentStreamEventType.RUN_CANCELLED);

            CircuitBreaker capacityCircuit = CircuitBreaker.ofDefaults("capacity");
            var capacity = resilient(raw, capacityCircuit, 1);
            RecordingListener slow = new RecordingListener();
            capacity.stream(command("capacity-1"), slow);
            assertThat(slow.started.await(2, TimeUnit.SECONDS)).isTrue();
            RecordingListener excess = run(capacity, "capacity-2");
            assertThat(excess.failure).isEqualTo(AgentRuntimeStreamFailureKind.CAPACITY_REJECTED);
            assertThat(slow.finished.await(3, TimeUnit.SECONDS)).isTrue();
        } finally {
            channel.shutdownNow().awaitTermination(3, TimeUnit.SECONDS);
            if (python != null) {
                python.destroy();
                if (!python.waitFor(3, TimeUnit.SECONDS)) {
                    python.destroyForcibly().waitFor(3, TimeUnit.SECONDS);
                }
            }
        }
    }

    private ResilientAgentRuntimeStreamingPort resilient(
            GrpcAgentRuntimeStreamingClient raw, CircuitBreaker circuit, int capacity) {
        Bulkhead bulkhead = Bulkhead.of("fault-injection-" + capacity, BulkheadConfig.custom()
                .maxConcurrentCalls(capacity).maxWaitDuration(Duration.ZERO).build());
        return new ResilientAgentRuntimeStreamingPort(raw, circuit, bulkhead,
                new AgentRuntimeResilienceMetrics(new SimpleMeterRegistry()));
    }

    private RecordingListener run(ResilientAgentRuntimeStreamingPort port, String runId)
            throws InterruptedException {
        RecordingListener listener = new RecordingListener();
        port.stream(command(runId), listener);
        assertThat(listener.finished.await(3, TimeUnit.SECONDS)).isTrue();
        return listener;
    }

    private AgentRunCommand command(String runId) {
        return new AgentRunCommand("request-" + runId, runId, "hello");
    }

    private int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private Process startPython(int port) throws IOException {
        Path root = Path.of(System.getProperty("user.dir")).toAbsolutePath();
        while (root != null && !Files.isDirectory(root.resolve("agent-service"))) {
            root = root.getParent();
        }
        if (root == null) {
            throw new IOException("cannot locate repository root");
        }
        Path configured = Path.of(System.getenv().getOrDefault(
                "DEVPILOT_AGENT_PYTHON", "agent-service/.venv/Scripts/python.exe"));
        Path executable = configured.isAbsolute() ? configured : root.resolve(configured);
        String python = Files.exists(executable) ? executable.toString() : "python";
        ProcessBuilder builder = new ProcessBuilder(
                python, "-m", "devpilot_agent_service.rpc.server");
        builder.directory(root.resolve("agent-service").toFile());
        builder.environment().put("PYTHONPATH", root.resolve("agent-service/src").toString());
        builder.environment().put("AGENT_GRPC_HOST", "127.0.0.1");
        builder.environment().put("AGENT_GRPC_PORT", Integer.toString(port));
        builder.environment().put("AGENT_MODEL_MODE", "fake");
        builder.environment().put("AGENT_FAKE_DELAY_SECONDS", "0.5");
        builder.redirectErrorStream(true);
        return builder.start();
    }

    private static final class RecordingListener implements AgentRuntimeEventListener {
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch finished = new CountDownLatch(1);
        private volatile AgentRuntimeStreamFailureKind failure;
        private volatile AgentStreamEventType terminal;

        @Override
        public void onEvent(AgentStreamEvent event) {
            if (event.type() == AgentStreamEventType.RUN_STARTED) {
                started.countDown();
            }
            if (event.type().isTerminal()) {
                terminal = event.type();
                finished.countDown();
            }
        }

        @Override
        public void onError(AgentRuntimeStreamFailureKind failureKind) {
            failure = failureKind;
            finished.countDown();
        }

        @Override
        public void onCompleted() {
            finished.countDown();
        }
    }
}
