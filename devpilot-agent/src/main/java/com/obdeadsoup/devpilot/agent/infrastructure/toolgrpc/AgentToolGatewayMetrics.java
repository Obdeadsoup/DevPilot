package com.obdeadsoup.devpilot.agent.infrastructure.toolgrpc;

import com.obdeadsoup.devpilot.agent.application.tool.AgentToolErrorKind;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolName;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.time.Duration;

/** Tool Gateway 低基数指标；所有 tag 只来自 allowlist 或稳定 failure kind。 */
public final class AgentToolGatewayMetrics {
    private final MeterRegistry registry;
    private final Counter authDenied;

    public AgentToolGatewayMetrics(MeterRegistry registry) {
        this.registry = registry;
        this.authDenied = Counter.builder("devpilot.agent.tool.gateway.auth.denied")
                .description("Rejected Python service identity calls")
                .register(registry);
    }

    public void recordAuthDenied() {
        authDenied.increment();
    }

    public void record(String rawToolName, boolean success, AgentToolErrorKind failureKind,
                       long startedNanos) {
        String toolName = AgentToolName.fromWireName(rawToolName)
                .map(AgentToolName::wireName).orElse("unknown");
        String result = success ? "success" : "failure";
        String failure = failureKind == null ? "none" : failureKind.name().toLowerCase();
        Counter.builder("devpilot.agent.tool.gateway.calls")
                .tag("tool_name", toolName).tag("result", result).tag("failure_kind", failure)
                .register(registry).increment();
        Timer.builder("devpilot.agent.tool.gateway.duration")
                .tag("tool_name", toolName).tag("result", result).tag("failure_kind", failure)
                .register(registry)
                .record(Duration.ofNanos(Math.max(0, System.nanoTime() - startedNanos)));
    }
}
