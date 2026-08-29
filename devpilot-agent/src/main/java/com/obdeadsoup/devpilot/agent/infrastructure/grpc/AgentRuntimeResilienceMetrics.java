package com.obdeadsoup.devpilot.agent.infrastructure.grpc;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.concurrent.atomic.AtomicInteger;

/** Agent Runtime 熔断、容量与活跃流指标；标签只包含稳定分类。 */
public final class AgentRuntimeResilienceMetrics {
    private final MeterRegistry registry;
    private final AtomicInteger activeStreams = new AtomicInteger();

    public AgentRuntimeResilienceMetrics(MeterRegistry registry) {
        this.registry = registry;
        Gauge.builder("agent.runtime.active.streams", activeStreams, AtomicInteger::get)
                .description("当前持有 Java Runtime 容量许可的 Agent 流")
                .register(registry);
    }

    void streamStarted() {
        activeStreams.incrementAndGet();
    }

    void streamFinished() {
        activeStreams.decrementAndGet();
    }

    void circuitCall(String outcome) {
        Counter.builder("agent.runtime.circuit.calls").tag("outcome", outcome).register(registry).increment();
    }

    void capacityRejected() {
        registry.counter("agent.runtime.capacity.rejected").increment();
    }
}
