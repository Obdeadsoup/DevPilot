package com.obdeadsoup.devpilot.outbox.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.util.Set;
import org.springframework.stereotype.Component;

/** Outbox 发布、处理和状态转换的低基数指标门面。 */
@Component
public class OutboxMetrics {

    private static final Set<String> EVENT_TYPES = Set.of(
            "TASK_ASSIGNED_V1", "TASK_UNASSIGNED_V1", "TASK_SUBMITTED_FOR_REVIEW_V1",
            "TASK_CHANGES_REQUESTED_V1", "TASK_COMPLETED_V1", "TASK_REOPENED_V1");
    private final MeterRegistry registry;

    public OutboxMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void published(String eventType, String result) {
        Counter.builder("devpilot.outbox.published").description("Outbox 事件发布累计次数")
                .tags("event_type", eventType(eventType), "result", result).register(registry).increment();
    }

    public void deduplicated(String eventType) {
        Counter.builder("devpilot.outbox.deduplicated").description("Outbox 唯一键去重累计次数")
                .tag("event_type", eventType(eventType)).register(registry).increment();
    }

    public void processed(String eventType) {
        Counter.builder("devpilot.outbox.processed").description("Outbox 成功处理累计次数")
                .tag("event_type", eventType(eventType)).register(registry).increment();
    }

    public void failed(String eventType, String failureType, boolean dead) {
        Counter.builder(dead ? "devpilot.outbox.dead.transitions" : "devpilot.outbox.retry.wait")
                .description(dead ? "Outbox 转入 DEAD 的历史累计次数" : "Outbox 转入 RETRY_WAIT 的累计次数")
                .tags("event_type", eventType(eventType), "failure_type", boundedFailure(failureType))
                .register(registry).increment();
    }

    public void processing(String eventType, String result, Duration duration) {
        Timer.builder("devpilot.outbox.processing")
                .description("Outbox Handler 与状态提交耗时")
                .tags("event_type", eventType(eventType), "result", result)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    private String eventType(String value) {
        return EVENT_TYPES.contains(value) ? value.toLowerCase(java.util.Locale.ROOT) : "other";
    }

    private String boundedFailure(String value) {
        return value == null || value.length() > 64 ? "other" : value.toLowerCase(java.util.Locale.ROOT);
    }
}
