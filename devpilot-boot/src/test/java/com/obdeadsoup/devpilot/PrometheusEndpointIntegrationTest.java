package com.obdeadsoup.devpilot;

import static org.assertj.core.api.Assertions.assertThat;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.http.HttpStatus;
import com.obdeadsoup.devpilot.github.application.GitHubDeliveryMetrics;
import com.obdeadsoup.devpilot.outbox.application.OutboxMetrics;
import com.obdeadsoup.devpilot.audit.application.AuditReplayMetrics;
import java.time.Duration;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles({"test", "observability"})
@Import(IsolatedPersistenceTestConfiguration.class)
class PrometheusEndpointIntegrationTest {
    @Autowired TestRestTemplate http;
    @Autowired GitHubDeliveryMetrics deliveryMetrics;
    @Autowired OutboxMetrics outboxMetrics;
    @Autowired AuditReplayMetrics replayMetrics;

    @Test
    void observabilityProfileExposesMetricsAndPrometheusWithoutSensitiveValues() throws Exception {
        assertThat(http.getForEntity("/actuator/health", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/health/liveness", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/health/readiness", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/actuator/metrics", String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        deliveryMetrics.processing("ping", "success", Duration.ofMillis(2));
        outboxMetrics.processed("TASK_ASSIGNED_V1");
        replayMetrics.record("outbox", "created");
        var metrics = http.getForEntity("/actuator/metrics/devpilot.outbox.backlog", String.class);
        assertThat(metrics.getStatusCode()).isEqualTo(HttpStatus.OK);
        var response = http.getForEntity("/actuator/prometheus", String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getHeaders().getContentType().toString()).contains("text/plain");
        String body = response.getBody();
        assertThat(body).contains(
                "devpilot_outbox_backlog",
                "devpilot_outbox_oldest_ready_age_seconds",
                "devpilot_github_delivery_processing_seconds_count",
                "devpilot_outbox_processed_total",
                "devpilot_audit_replay_total",
                "http_server_requests_seconds_count",
                "jvm_memory_used_bytes",
                "system_cpu_count");
        assertThat(body).doesNotContain("credential_ref", "correlationId", "repositoryFullName", "token=");
    }
}
