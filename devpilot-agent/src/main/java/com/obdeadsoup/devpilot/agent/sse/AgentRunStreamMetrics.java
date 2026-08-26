package com.obdeadsoup.devpilot.agent.sse;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** Agent SSE 仅记录低基数 channel/result，不把 runId、输入或输出放入指标标签。 */
@Component
public class AgentRunStreamMetrics {
    private final MeterRegistry registry;

    public AgentRunStreamMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void send(String channel, boolean success) {
        registry.counter("devpilot.agent.sse.send", "channel", channel,
                "result", success ? "success" : "failure").increment();
    }
}
