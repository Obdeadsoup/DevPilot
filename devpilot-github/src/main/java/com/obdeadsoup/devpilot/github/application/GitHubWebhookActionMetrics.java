package com.obdeadsoup.devpilot.github.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Component;

/** 未支持 action 只按固定 event 维度计数，禁止把任意 action 或 Payload 变成指标标签。 */
@Component
public class GitHubWebhookActionMetrics {
    private final MeterRegistry registry;
    public GitHubWebhookActionMetrics(MeterRegistry registry){this.registry=registry;}
    public void unsupported(String eventType){Counter.builder("devpilot.github.webhook.unsupported.action")
            .description("已知 Webhook 事件中未支持 action 的累计次数")
            .tag("event_type", eventType).register(registry).increment();}
}
