package com.obdeadsoup.devpilot.agent.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** HTTP→Runtime 取消请求的稳定结果指标。 */
@Component
public class AgentRunCancellationMetrics {
    private final MeterRegistry registry;

    public AgentRunCancellationMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void requested() {
        registry.counter("agent.run.cancel.requests").increment();
    }

    public void accepted() {
        registry.counter("agent.run.cancel.accepted").increment();
    }

    public void failed() {
        registry.counter("agent.run.cancel.failed").increment();
    }
}
