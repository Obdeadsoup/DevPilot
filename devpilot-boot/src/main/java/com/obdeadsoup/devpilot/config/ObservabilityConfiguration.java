package com.obdeadsoup.devpilot.config;

import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.config.MeterFilterReply;
import java.util.Set;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * 全局拒绝明显的实体标识标签，作为模块 metrics façade 之外的第二道基数防线。
 * Correlation ID、用户/工作区/项目及各类数据库 ID 都只应进入安全日志，不进入 Meter。
 */
@Configuration(proxyBeanMethods = false)
public class ObservabilityConfiguration {

    private static final Set<String> FORBIDDEN_TAG_KEYS = Set.of(
            "userId", "user_id", "workspaceId", "workspace_id", "projectId", "project_id",
            "taskId", "task_id", "repositoryId", "repository_id", "bindingId", "binding_id",
            "deliveryId", "delivery_id", "outboxId", "outbox_id", "runId", "run_id",
            "auditId", "audit_id", "correlationId", "correlation_id", "eventKey", "event_key",
            "dedupeKey", "dedupe_key", "requestId", "request_id");

    @Bean
    MeterFilter rejectHighCardinalityBusinessTags() {
        return new MeterFilter() {
            @Override
            public MeterFilterReply accept(Meter.Id id) {
                return id.getTags().stream().anyMatch(tag -> FORBIDDEN_TAG_KEYS.contains(tag.getKey()))
                        ? MeterFilterReply.DENY : MeterFilterReply.NEUTRAL;
            }
        };
    }
}
