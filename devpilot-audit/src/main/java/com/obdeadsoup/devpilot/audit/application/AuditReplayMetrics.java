package com.obdeadsoup.devpilot.audit.application;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Counter;
import org.springframework.stereotype.Component;

/** 人工 Replay 请求结果的低基数指标门面；reason、用户和资源 ID 永不进入标签。 */
@Component
public class AuditReplayMetrics {

    private final MeterRegistry registry;

    public AuditReplayMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void record(String resourceType, String result) {
        Counter.builder("devpilot.audit.replay").description("人工 Replay 请求结果累计次数")
                .tags("resource_type", resourceType, "result", result).register(registry).increment();
    }
}
