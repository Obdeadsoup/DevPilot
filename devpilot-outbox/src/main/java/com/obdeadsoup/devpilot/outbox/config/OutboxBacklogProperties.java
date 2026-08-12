package com.obdeadsoup.devpilot.outbox.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Outbox backlog 快照刷新频率与陈旧阈值。 */
@ConfigurationProperties("devpilot.observability.backlog")
public record OutboxBacklogProperties(boolean enabled, Duration refreshInterval, Duration staleAfter) {
    public OutboxBacklogProperties {
        refreshInterval = refreshInterval == null ? Duration.ofSeconds(30) : refreshInterval;
        staleAfter = staleAfter == null ? Duration.ofMinutes(2) : staleAfter;
    }
}
