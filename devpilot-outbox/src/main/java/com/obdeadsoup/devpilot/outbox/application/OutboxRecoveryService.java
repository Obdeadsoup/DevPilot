package com.obdeadsoup.devpilot.outbox.application;

import com.obdeadsoup.devpilot.outbox.config.OutboxProperties;
import com.obdeadsoup.devpilot.outbox.persistence.mapper.OutboxEventMapper;
import java.time.Clock;
import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.core.task.TaskRejectedException;
import org.springframework.stereotype.Service;

/**
 * 数据库扫描是 Outbox 的可靠兜底：恢复 stale PROCESSING，并重新提交 PENDING/到期 RETRY_WAIT。
 * Executor 拒绝发生在 claim 前，因此行仍保持可扫描状态。
 */
@Service
public class OutboxRecoveryService {

    private static final Logger log = LoggerFactory.getLogger(OutboxRecoveryService.class);

    private final OutboxEventMapper mapper;
    private final OutboxStateService stateService;
    private final OutboxWorker worker;
    private final TaskExecutor executor;
    private final OutboxProperties properties;
    private final Clock clock;

    public OutboxRecoveryService(
            OutboxEventMapper mapper,
            OutboxStateService stateService,
            OutboxWorker worker,
            @Qualifier("outboxTaskExecutor") TaskExecutor executor,
            OutboxProperties properties,
            Clock clock) {
        this.mapper = mapper;
        this.stateService = stateService;
        this.worker = worker;
        this.executor = executor;
        this.properties = properties;
        this.clock = clock;
    }

    public void recover() {
        LocalDateTime now = LocalDateTime.now(clock);
        LocalDateTime cutoff = now.minus(properties.processingTimeout());
        mapper.findStaleProcessing(cutoff, properties.batchSize())
                .forEach(event -> stateService.recoverStale(event, cutoff));

        Set<Long> candidates = new LinkedHashSet<>(mapper.findPendingIds(properties.batchSize()));
        candidates.addAll(mapper.findDueRetryIds(now, properties.batchSize()));
        candidates.forEach(this::submit);
    }

    public void submit(long outboxId) {
        try {
            executor.execute(() -> worker.process(outboxId));
        } catch (TaskRejectedException exception) {
            log.warn("Outbox executor rejected outboxId={} failureType={}",
                    outboxId, exception.getClass().getSimpleName());
        }
    }
}
