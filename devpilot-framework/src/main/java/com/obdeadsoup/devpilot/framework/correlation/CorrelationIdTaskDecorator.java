package com.obdeadsoup.devpilot.framework.correlation;

import org.springframework.core.task.TaskDecorator;
import org.springframework.stereotype.Component;

/**
 * 只传播有限 MDC 字段 correlationId；不复制 SecurityContext、Token 或 userId。
 * Scheduler 提交任务时没有请求上下文，因此为该次后台操作生成独立 ID。
 */
@Component
public class CorrelationIdTaskDecorator implements TaskDecorator {

    private final CorrelationIdPolicy policy;
    private final CorrelationIdAccessor accessor;

    public CorrelationIdTaskDecorator(CorrelationIdPolicy policy, CorrelationIdAccessor accessor) {
        this.policy = policy;
        this.accessor = accessor;
    }

    @Override
    public Runnable decorate(Runnable runnable) {
        String captured = accessor.current().orElseGet(policy::generate);
        return withCorrelationId(runnable, captured);
    }

    /** Durable event Worker 不依赖原请求 ThreadLocal，每次提交都建立新的处理关联 ID。 */
    public Runnable decorateFresh(Runnable runnable) {
        return withCorrelationId(runnable, policy.generate());
    }

    private Runnable withCorrelationId(Runnable runnable, String correlationId) {
        return () -> {
            try (CorrelationIdAccessor.Scope ignored = accessor.open(correlationId)) {
                runnable.run();
            }
        };
    }
}
