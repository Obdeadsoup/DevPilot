package com.obdeadsoup.devpilot.github.application;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/** 未支持 action 只按固定 event 维度计数，禁止把任意 action 或 Payload 变成指标标签。 */
@Component
public class GitHubWebhookActionMetrics {
    private final MeterRegistry registry;
    public GitHubWebhookActionMetrics(MeterRegistry registry){this.registry=registry;}
    public void unsupported(String eventType){registry.counter("devpilot.github.webhook.unsupported_action",
            "event",eventType).increment();}
}
