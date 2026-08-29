package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeEventListener;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamFailureKind;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamHandle;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamingPort;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEvent;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEventType;
import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientAgentRuntimeStreamingPortTest {

    @Test
    void bulkheadRejectsAtCapacityAndReleasesOnCancel() {
        RetainingPort delegate = new RetainingPort();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResilientAgentRuntimeStreamingPort port = port(delegate, registry, 1, 10);
        RecordingListener first = new RecordingListener();
        AgentRuntimeStreamHandle handle = port.stream(command("run-1"), first);

        RecordingListener rejected = new RecordingListener();
        port.stream(command("run-2"), rejected);
        assertThat(delegate.listeners).hasSize(1);
        assertThat(rejected.failure).isEqualTo(AgentRuntimeStreamFailureKind.CAPACITY_REJECTED);
        assertThat(registry.counter("agent.runtime.capacity.rejected").count()).isEqualTo(1);

        handle.cancel();
        port.stream(command("run-3"), new RecordingListener());
        assertThat(delegate.listeners).hasSize(2);
        assertThat(registry.get("agent.runtime.active.streams").gauge().value()).isEqualTo(1);
    }

    @Test
    void transportFailuresOpenCircuitAndPreventNetworkCall() {
        FailingPort delegate = new FailingPort();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResilientAgentRuntimeStreamingPort port = port(delegate, registry, 5, 2);

        port.stream(command("run-1"), new RecordingListener());
        port.stream(command("run-2"), new RecordingListener());
        RecordingListener rejected = new RecordingListener();
        port.stream(command("run-3"), rejected);

        assertThat(delegate.calls).isEqualTo(2);
        assertThat(rejected.failure).isEqualTo(AgentRuntimeStreamFailureKind.CIRCUIT_OPEN);
        assertThat(registry.get("agent.runtime.circuit.calls")
                .tag("outcome", "not_permitted").counter().count()).isEqualTo(1);
        assertThat(registry.get("agent.runtime.active.streams").gauge().value()).isZero();
    }

    @Test
    void normalTerminalIsRecordedAsSuccessAndReleasesCapacity() {
        RetainingPort delegate = new RetainingPort();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CircuitBreaker circuit = circuit(2);
        ResilientAgentRuntimeStreamingPort port = port(delegate, registry, circuit, 1);

        port.stream(command("run-1"), new RecordingListener());
        delegate.listeners.getFirst().onEvent(new AgentStreamEvent(
                "run-1:1", "run-1", 1, AgentStreamEventType.RUN_SUCCEEDED,
                0, "", "done", ""));
        port.stream(command("run-2"), new RecordingListener());

        assertThat(delegate.listeners).hasSize(2);
        assertThat(circuit.getMetrics().getNumberOfSuccessfulCalls()).isEqualTo(1);
        assertThat(registry.get("agent.runtime.active.streams").gauge().value()).isEqualTo(1);
    }

    @Test
    void invalidArgumentAndUserCancelDoNotCountAsDependencyFailures() {
        RetainingPort delegate = new RetainingPort();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CircuitBreaker circuit = circuit(2);
        ResilientAgentRuntimeStreamingPort port = port(delegate, registry, circuit, 2);

        port.stream(command("run-1"), new RecordingListener());
        delegate.listeners.getFirst().onError(AgentRuntimeStreamFailureKind.INVALID_ARGUMENT);
        AgentRuntimeStreamHandle handle = port.stream(command("run-2"), new RecordingListener());
        handle.cancel();

        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(circuit.getMetrics().getNumberOfFailedCalls()).isZero();
        assertThat(registry.get("agent.runtime.circuit.calls")
                .tag("outcome", "ignored").counter().count()).isEqualTo(2);
        assertThat(registry.get("agent.runtime.active.streams").gauge().value()).isZero();
    }

    @Test
    void halfOpenSuccessClosesCircuitWithoutSleeping() {
        RetainingPort delegate = new RetainingPort();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        CircuitBreaker circuit = circuit(1);
        circuit.transitionToOpenState();
        circuit.transitionToHalfOpenState();
        ResilientAgentRuntimeStreamingPort port = port(delegate, registry, circuit, 1);

        port.stream(command("probe"), new RecordingListener());
        delegate.listeners.getFirst().onCompleted();

        assertThat(circuit.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(registry.get("agent.runtime.active.streams").gauge().value()).isZero();
    }

    @Test
    void throwingDelegateCancelStillReleasesCapacity() {
        AtomicInteger calls = new AtomicInteger();
        AgentRuntimeStreamingPort delegate = (command, listener) -> {
            calls.incrementAndGet();
            return () -> {
                throw new IllegalStateException("cancel failed");
            };
        };
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ResilientAgentRuntimeStreamingPort port = port(delegate, registry, 1, 10);
        AgentRuntimeStreamHandle first = port.stream(command("run-1"), new RecordingListener());

        assertThatThrownBy(first::cancel).isInstanceOf(IllegalStateException.class);
        port.stream(command("run-2"), new RecordingListener());

        assertThat(calls).hasValue(2);
        assertThat(registry.get("agent.runtime.active.streams").gauge().value()).isEqualTo(1);
    }

    private ResilientAgentRuntimeStreamingPort port(AgentRuntimeStreamingPort delegate,
                                                     SimpleMeterRegistry registry,
                                                     int capacity,
                                                     int minimumCalls) {
        return port(delegate, registry, circuit(minimumCalls), capacity);
    }

    private CircuitBreaker circuit(int minimumCalls) {
        return CircuitBreaker.of("test-" + minimumCalls, CircuitBreakerConfig.custom()
                .slidingWindowSize(minimumCalls)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(50)
                .waitDurationInOpenState(Duration.ofSeconds(10))
                .permittedNumberOfCallsInHalfOpenState(1)
                .build());
    }

    private ResilientAgentRuntimeStreamingPort port(AgentRuntimeStreamingPort delegate,
                                                     SimpleMeterRegistry registry,
                                                     CircuitBreaker circuit,
                                                     int capacity) {
        Bulkhead bulkhead = Bulkhead.of("test", BulkheadConfig.custom()
                .maxConcurrentCalls(capacity)
                .maxWaitDuration(Duration.ZERO)
                .build());
        return new ResilientAgentRuntimeStreamingPort(
                delegate, circuit, bulkhead, new AgentRuntimeResilienceMetrics(registry));
    }

    private AgentRunCommand command(String runId) {
        return new AgentRunCommand("request-" + runId, runId, "hello");
    }

    private static final class RetainingPort implements AgentRuntimeStreamingPort {
        private final List<AgentRuntimeEventListener> listeners = new ArrayList<>();

        @Override
        public AgentRuntimeStreamHandle stream(AgentRunCommand command, AgentRuntimeEventListener listener) {
            listeners.add(listener);
            return AgentRuntimeStreamHandle.NOOP;
        }
    }

    private static final class FailingPort implements AgentRuntimeStreamingPort {
        private int calls;

        @Override
        public AgentRuntimeStreamHandle stream(AgentRunCommand command, AgentRuntimeEventListener listener) {
            calls++;
            listener.onError(AgentRuntimeStreamFailureKind.UNAVAILABLE);
            return AgentRuntimeStreamHandle.NOOP;
        }
    }

    private static final class RecordingListener implements AgentRuntimeEventListener {
        private AgentRuntimeStreamFailureKind failure;

        @Override
        public void onEvent(AgentStreamEvent event) {
        }

        @Override
        public void onError(AgentRuntimeStreamFailureKind failureKind) {
            failure = failureKind;
        }

        @Override
        public void onCompleted() {
        }
    }
}
