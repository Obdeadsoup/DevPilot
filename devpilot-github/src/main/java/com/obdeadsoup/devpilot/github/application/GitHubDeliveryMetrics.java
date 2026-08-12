package com.obdeadsoup.devpilot.github.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Delivery 处理链的低基数指标门面；外部任意 event 值会收敛为 other。 */
@Component
public class GitHubDeliveryMetrics {

    private static final Set<String> EVENT_TYPES = Set.of(
            "ping", "push", "issues", "pull_request", "pull_request_review");
    private final MeterRegistry registry;

    public GitHubDeliveryMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void processing(String eventType, String result, Duration duration) {
        Timer.builder("devpilot.github.delivery.processing")
                .description("GitHub Delivery 业务处理耗时")
                .tags("event_type", boundedEventType(eventType), "result", result)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void transitionedToDead(String eventType) {
        Counter.builder("devpilot.github.delivery.dead.transitions")
                .description("Delivery 转入 DEAD 的历史累计次数")
                .tag("event_type", boundedEventType(eventType)).register(registry).increment();
    }

    private String boundedEventType(String eventType) {
        return EVENT_TYPES.contains(eventType) ? eventType : "other";
    }
}
