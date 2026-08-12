package com.obdeadsoup.devpilot.github.config;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** GitHub backlog 快照刷新频率与陈旧阈值。 */
@ConfigurationProperties("devpilot.observability.backlog")
public record GitHubBacklogProperties(boolean enabled, Duration refreshInterval, Duration staleAfter) {

    public GitHubBacklogProperties {
        refreshInterval = refreshInterval == null ? Duration.ofSeconds(30) : refreshInterval;
        staleAfter = staleAfter == null ? Duration.ofMinutes(2) : staleAfter;
    }
}
