package com.obdeadsoup.devpilot.github;

import static org.assertj.core.api.Assertions.assertThat;

import com.obdeadsoup.devpilot.github.application.GitHubBacklogSnapshotService;
import com.obdeadsoup.devpilot.outbox.application.OutboxBacklogSnapshotService;
import java.time.LocalDateTime;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration-test")
@SpringBootTest
class BacklogSnapshotIntegrationTest {
    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_backlog_test")
            .withUsername("devpilot")
            .withPassword("devpilot_test_password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired JdbcTemplate jdbc;
    @Autowired GitHubBacklogSnapshotService github;
    @Autowired OutboxBacklogSnapshotService outbox;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM dp_audit_log");
        jdbc.update("DELETE FROM dp_outbox_event WHERE replay_of_event_id IS NOT NULL");
        jdbc.update("DELETE FROM dp_outbox_event");
        WebhookTestFixture fixture = new WebhookTestFixture(jdbc);
        fixture.reset();
        fixture.createActiveBinding();
    }

    @Test
    void distinguishesReadyFutureRetryStaleAndResolvedDead() {
        LocalDateTime now = LocalDateTime.now();
        insertDelivery("received", "RECEIVED", null, null);
        insertDelivery("retry-due", "RETRY_WAIT", now.minusMinutes(1), null);
        insertDelivery("retry-future", "RETRY_WAIT", now.plusMinutes(5), null);
        insertDelivery("processing-stale", "PROCESSING", null, now.minusMinutes(5));
        insertDelivery("dead", "DEAD", null, null);

        long syncOpenDead = insertSync("COMMIT", "SCHEDULED", "DEAD", null, null, null);
        long syncResolvedDead = insertSync("ISSUE", "SCHEDULED", "DEAD", null, null, null);
        insertSync("ISSUE", "MANUAL_REPLAY", "SUCCEEDED", null, null, syncResolvedDead);
        insertSync("PULL_REQUEST", "SCHEDULED", "PENDING", null, null, null);
        insertSync("PULL_REQUEST_REVIEW", "SCHEDULED", "RETRY_WAIT", now.minusMinutes(1), null, null);
        insertSync("COMMIT", "SCHEDULED", "RUNNING", null, now.minusMinutes(20), null);

        long outboxOpenDead = insertOutbox("dead-open", "DEAD", null, null, null);
        long outboxResolvedDead = insertOutbox("dead-resolved", "DEAD", null, null, null);
        insertOutbox("replay-success", "PROCESSED", null, null, outboxResolvedDead);
        insertOutbox("pending", "PENDING", null, null, null);
        insertOutbox("retry-due", "RETRY_WAIT", now.minusMinutes(1), null, null);
        insertOutbox("retry-future", "RETRY_WAIT", now.plusMinutes(5), null, null);
        insertOutbox("processing-stale", "PROCESSING", null, now.minusMinutes(5), null);

        github.refresh();
        outbox.refresh();

        assertThat(github.current().deliveryReceived()).isEqualTo(1);
        assertThat(github.current().deliveryRetryDue()).isEqualTo(1);
        assertThat(github.current().deliveryStaleProcessing()).isEqualTo(1);
        assertThat(github.current().deliveryOpenDead()).isEqualTo(1);
        assertThat(github.current().deliveryOldestReadyAgeSeconds()).isPositive();
        assertThat(github.current().syncPending()).isEqualTo(1);
        assertThat(github.current().syncRetryDue()).isEqualTo(1);
        assertThat(github.current().syncStaleRunning()).isEqualTo(1);
        assertThat(github.current().syncOpenDead()).isEqualTo(1);
        assertThat(syncOpenDead).isPositive();

        assertThat(outbox.current().pending()).isEqualTo(1);
        assertThat(outbox.current().retryDue()).isEqualTo(1);
        assertThat(outbox.current().staleProcessing()).isEqualTo(1);
        assertThat(outbox.current().openDead()).isEqualTo(1);
        assertThat(outbox.current().oldestReadyAgeSeconds()).isPositive();
        assertThat(outboxOpenDead).isPositive();
    }

    private void insertDelivery(String id, String status, LocalDateTime retryAt, LocalDateTime startedAt) {
        jdbc.update("""
                INSERT INTO dp_github_delivery(
                  workspace_id,project_id,repository_id,github_delivery_id,event_type,signature_status,
                  processing_status,payload_json,payload_sha256,retry_count,next_retry_at,processing_started_at,received_at)
                VALUES(100,200,300,?,'ping','VALID',?,JSON_OBJECT('zen','test'),?,0,?,?,?)
                """, id, status, "0".repeat(64), retryAt, startedAt, LocalDateTime.now().minusMinutes(2));
    }

    private long insertSync(String resource, String trigger, String status, LocalDateTime retryAt,
                            LocalDateTime startedAt, Long replayOf) {
        jdbc.update("""
                INSERT INTO dp_github_sync_run(
                  repository_binding_id,resource_type,trigger_type,status,next_retry_at,started_at,
                  replay_of_run_id,replay_sequence,replay_requested_by,replay_reason)
                VALUES(300,?,?,?,?,?,?,IF(? IS NULL,0,1),IF(? IS NULL,NULL,10),IF(? IS NULL,NULL,'test replay'))
                """, resource, trigger, status, retryAt, startedAt, replayOf, replayOf, replayOf, replayOf);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }

    private long insertOutbox(String key, String status, LocalDateTime retryAt,
                              LocalDateTime startedAt, Long replayOf) {
        jdbc.update("""
                INSERT INTO dp_outbox_event(
                  event_key,aggregate_type,aggregate_id,event_type,schema_version,payload_json,
                  processing_status,retry_count,next_retry_at,processing_started_at,occurred_at,
                  replay_of_event_id,replay_sequence,replay_requested_by,replay_reason)
                VALUES(?,'TASK',1,'TASK_ASSIGNED',1,JSON_OBJECT('taskId',1),?,0,?,?,?,
                       ?,IF(? IS NULL,0,1),IF(? IS NULL,NULL,10),IF(? IS NULL,NULL,'test replay'))
                """, key, status, retryAt, startedAt, LocalDateTime.now().minusMinutes(2),
                replayOf, replayOf, replayOf, replayOf);
        return jdbc.queryForObject("SELECT LAST_INSERT_ID()", Long.class);
    }
}
