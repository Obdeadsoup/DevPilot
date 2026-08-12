package com.obdeadsoup.devpilot.github.application;

import com.obdeadsoup.devpilot.github.domain.GitHubSyncResourceType;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncTriggerType;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.Counter;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Locale;
import org.springframework.stereotype.Component;

/** Sync Run 状态机的低基数 Timer/Counter 门面，不注册 bindingId、runId 或 Repository 标签。 */
@Component
public class GitHubSyncMetrics {

    private final MeterRegistry registry;

    public GitHubSyncMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    public void completed(GitHubSyncRunEntity run, String result, LocalDateTime completedAt) {
        LocalDateTime startedAt = run.startedAt();
        Duration duration = startedAt == null || completedAt.isBefore(startedAt)
                ? Duration.ZERO : Duration.between(startedAt, completedAt);
        Timer.builder("devpilot.github.sync.run.duration")
                .description("GitHub Sync Run 从 claim 到本次终止状态的耗时")
                .tags(
                        "resource_type", resourceType(run.resourceType()),
                        "trigger_type", triggerType(run.triggerType()),
                        "result", result)
                .publishPercentileHistogram()
                .register(registry)
                .record(duration);
    }

    public void transitionedToDead(GitHubSyncRunEntity run) {
        Counter.builder("devpilot.github.sync.dead.transitions")
                .description("Sync Run 转入 DEAD 的历史累计次数")
                .tags("resource_type", resourceType(run.resourceType()),
                        "trigger_type", triggerType(run.triggerType()))
                .register(registry).increment();
    }

    private String resourceType(String value) {
        try {
            return GitHubSyncResourceType.valueOf(value).name().toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "other";
        }
    }

    private String triggerType(String value) {
        try {
            return GitHubSyncTriggerType.valueOf(value).name().toLowerCase(Locale.ROOT);
        } catch (RuntimeException exception) {
            return "other";
        }
    }
}
