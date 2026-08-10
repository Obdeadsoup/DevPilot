package com.obdeadsoup.devpilot.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.obdeadsoup.devpilot.DevPilotApplication;
import com.obdeadsoup.devpilot.outbox.application.OutboxFailureDecision;
import com.obdeadsoup.devpilot.outbox.application.OutboxStateService;
import com.obdeadsoup.devpilot.outbox.domain.OutboxEventStatus;
import com.obdeadsoup.devpilot.outbox.domain.OutboxFailureType;
import com.obdeadsoup.devpilot.outbox.persistence.entity.OutboxEventEntity;
import com.obdeadsoup.devpilot.outbox.persistence.mapper.OutboxEventMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
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
@SpringBootTest(classes = DevPilotApplication.class)
class OutboxStateMachineIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_outbox_test")
            .withUsername("devpilot")
            .withPassword("devpilot_test_password");

    @DynamicPropertySource
    static void database(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired private JdbcTemplate jdbc;
    @Autowired private OutboxEventMapper mapper;
    @Autowired private OutboxStateService states;

    @BeforeEach
    void clear() {
        jdbc.update("DELETE FROM dp_outbox_event");
    }

    @Test
    void pendingCanBeClaimedOnlyOnceByConcurrentWorkers() throws Exception {
        long id = insert("concurrent", "PENDING", 0, null, null, 0);
        var pool = Executors.newFixedThreadPool(2);
        try {
            CountDownLatch start = new CountDownLatch(1);
            Callable<Boolean> claim = () -> {
                start.await();
                return states.claim(id).isPresent();
            };
            var first = pool.submit(claim);
            var second = pool.submit(claim);
            start.countDown();
            assertThat(List.of(first.get(), second.get())).containsExactlyInAnyOrder(true, false);
            assertThat(status(id)).isEqualTo("PROCESSING");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void retryWaitHonorsDueTimeAndPermanentFailureBecomesDead() {
        long future = insert("future", "RETRY_WAIT", 1, LocalDateTime.now().plusMinutes(5), null, 2);
        assertThat(states.claim(future)).isEmpty();

        long due = insert("due", "RETRY_WAIT", 1, LocalDateTime.now().minusSeconds(1), null, 2);
        OutboxEventEntity claimed = states.claim(due).orElseThrow();
        assertThat(claimed.getVersion()).isEqualTo(3);
        OutboxEventStatus result = states.markFailed(claimed, new OutboxFailureDecision(
                OutboxFailureType.INVALID_EVENT, false, "INVALID_EVENT", "Invalid outbox event"));
        assertThat(result).isEqualTo(OutboxEventStatus.DEAD);
        assertThat(status(due)).isEqualTo("DEAD");
    }

    @Test
    void staleProcessingRecoversAndOldVersionCannotOverwriteRecoveredState() {
        LocalDateTime started = LocalDateTime.now().minusMinutes(10);
        long id = insert("stale", "PROCESSING", 0, null, started, 4);
        OutboxEventEntity stale = mapper.findById(id).orElseThrow();

        assertThat(states.recoverStale(stale, LocalDateTime.now().minusMinutes(2)))
                .isEqualTo(OutboxEventStatus.RETRY_WAIT);
        assertThat(status(id)).isEqualTo("RETRY_WAIT");
        assertThat(mapper.markProcessed(id, 4, LocalDateTime.now())).isZero();
        assertThat(mapper.findById(id).orElseThrow().getVersion()).isEqualTo(5);
    }

    private long insert(
            String suffix,
            String status,
            int retryCount,
            LocalDateTime nextRetryAt,
            LocalDateTime processingStartedAt,
            long version) {
        jdbc.update("""
                INSERT INTO dp_outbox_event(
                    event_key,aggregate_type,aggregate_id,event_type,schema_version,payload_json,
                    processing_status,retry_count,next_retry_at,processing_started_at,occurred_at,version)
                VALUES(?, 'TASK', 103, 'TASK_ASSIGNED_V1', 1, JSON_OBJECT('taskId',103),
                       ?, ?, ?, ?, NOW(6), ?)
                """, "test:" + suffix, status, retryCount, nextRetryAt, processingStartedAt, version);
        return jdbc.queryForObject("SELECT id FROM dp_outbox_event WHERE event_key=?", Long.class, "test:" + suffix);
    }

    private String status(long id) {
        return jdbc.queryForObject(
                "SELECT processing_status FROM dp_outbox_event WHERE id=?", String.class, id);
    }
}
