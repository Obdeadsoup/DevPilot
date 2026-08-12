package com.obdeadsoup.devpilot.github.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.obdeadsoup.devpilot.github.config.GitHubBacklogProperties;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryBacklogQuery;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncBacklogQuery;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubBacklogMapper;
import com.obdeadsoup.devpilot.github.support.GitHubTestProperties;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class GitHubBacklogSnapshotServiceTest {
    @Test
    void refreshCalculatesAgeAndFailureKeepsLastGoodSnapshot() {
        GitHubBacklogMapper mapper = mock(GitHubBacklogMapper.class);
        GitHubDeliveryBacklogQuery delivery = new GitHubDeliveryBacklogQuery();
        delivery.setReceivedCount(2); delivery.setRetryWaitDueCount(1);
        delivery.setOldestReadyAt(LocalDateTime.of(2026, 8, 11, 11, 59));
        GitHubSyncBacklogQuery sync = new GitHubSyncBacklogQuery();
        sync.setPendingCount(3); sync.setOpenDeadCount(1);
        when(mapper.delivery(any(), any())).thenReturn(delivery);
        when(mapper.sync(any(), any())).thenReturn(sync);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Clock clock = Clock.fixed(Instant.parse("2026-08-11T12:00:00Z"), ZoneOffset.UTC);
        GitHubBacklogSnapshotService service = new GitHubBacklogSnapshotService(
                mapper, GitHubTestProperties.defaults(), GitHubTestProperties.reconciliation(),
                new GitHubBacklogProperties(false, Duration.ofSeconds(30), Duration.ofMinutes(2)),
                registry, clock);

        service.refresh();
        assertThat(service.current().deliveryReceived()).isEqualTo(2);
        assertThat(service.current().deliveryOldestReadyAgeSeconds()).isEqualTo(60);
        assertThat(service.current().syncOpenDead()).isEqualTo(1);
        when(mapper.delivery(any(), any())).thenThrow(new IllegalStateException("db down"));
        service.refresh();
        assertThat(service.current().deliveryReceived()).isEqualTo(2);
        assertThat(registry.get("devpilot.github.backlog.refresh.failures").counter().count()).isEqualTo(1);
    }
}
