package com.obdeadsoup.devpilot.outbox.application;

import com.obdeadsoup.devpilot.outbox.domain.OutboxEventStatus;
import com.obdeadsoup.devpilot.outbox.persistence.entity.OutboxEventEntity;
import java.time.Duration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Worker 先以数据库条件 claim 取得所有权，再进入独立处理事务；失败状态由独立事务记录。 */
@Component
public class OutboxWorker {

    private static final Logger log = LoggerFactory.getLogger(OutboxWorker.class);

    private final OutboxStateService stateService;
    private final OutboxDispatchService dispatchService;
    private final OutboxFailureClassifier failureClassifier;
    private final OutboxMetrics metrics;

    public OutboxWorker(
            OutboxStateService stateService,
            OutboxDispatchService dispatchService,
            OutboxFailureClassifier failureClassifier,
            OutboxMetrics metrics) {
        this.stateService = stateService;
        this.dispatchService = dispatchService;
        this.failureClassifier = failureClassifier;
        this.metrics = metrics;
    }

    public void process(long outboxId) {
        OutboxEventEntity event = stateService.claim(outboxId).orElse(null);
        if (event == null) {
            return;
        }
        long started = System.nanoTime();
        String metricResult = "processed";
        try {
            dispatchService.dispatch(event);
            log.info(
                    "Outbox processed outboxId={} eventType={} aggregateType={} aggregateId={} attempt={} result=PROCESSED durationMs={}",
                    event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(),
                    event.getRetryCount() + 1, elapsedMillis(started));
        } catch (RuntimeException exception) {
            OutboxFailureDecision decision = failureClassifier.classify(exception);
            OutboxEventStatus failureResult = stateService.markFailed(event, decision);
            String failureMetricResult = failureResult == OutboxEventStatus.DEAD ? "dead" : "retry_wait";
            log.warn(
                    "Outbox failed outboxId={} eventType={} aggregateType={} aggregateId={} attempt={} result={} errorCode={} durationMs={}",
                    event.getId(), event.getEventType(), event.getAggregateType(), event.getAggregateId(),
                    event.getRetryCount() + 1, failureResult, decision.errorCode(), elapsedMillis(started));
            this.metrics.processing(event.getEventType(), failureMetricResult,
                    Duration.ofNanos(System.nanoTime() - started));
            return;
        }
        metrics.processing(event.getEventType(), metricResult, Duration.ofNanos(System.nanoTime() - started));
    }

    private long elapsedMillis(long started) {
        return Duration.ofNanos(System.nanoTime() - started).toMillis();
    }
}
