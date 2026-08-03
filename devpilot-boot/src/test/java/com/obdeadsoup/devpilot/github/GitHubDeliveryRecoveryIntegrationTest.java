package com.obdeadsoup.devpilot.github;

import com.obdeadsoup.devpilot.github.application.GitHubDeliveryFailureClassifier;
import com.obdeadsoup.devpilot.github.application.GitHubDeliveryRecoveryService;
import com.obdeadsoup.devpilot.github.application.GitHubDeliveryStateService;
import com.obdeadsoup.devpilot.github.application.GitHubDeliveryWorker;
import com.obdeadsoup.devpilot.github.domain.GitHubDeliveryStatus;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration-test")
@SpringBootTest
class GitHubDeliveryRecoveryIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_recovery_test")
            .withUsername("devpilot")
            .withPassword("devpilot_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GitHubDeliveryMapper deliveryMapper;

    @Autowired
    private GitHubDeliveryStateService stateService;

    @Autowired
    private GitHubDeliveryWorker worker;

    @Autowired
    private GitHubDeliveryRecoveryService recoveryService;

    @Autowired
    private Clock clock;

    private WebhookTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new WebhookTestFixture(jdbcTemplate);
        fixture.reset();
        fixture.createActiveBinding();
    }

    @Test
    void recoversReceivedDeliveryWithoutSpringEventAndProcessesItToSucceeded() {
        insertDelivery("recovery-received", "ping", pingPayload());

        recoveryService.recover();

        await().untilAsserted(() -> {
            GitHubDeliveryEntity delivery = delivery("recovery-received");
            assertThat(delivery.processingStatus()).isEqualTo("SUCCEEDED");
            assertThat(delivery.retryCount()).isZero();
            assertThat(delivery.nextRetryAt()).isNull();
            assertThat(delivery.lastErrorCode()).isNull();
            assertThat(delivery.version()).isEqualTo(2);
            assertThat(activityCount("recovery-received")).isEqualTo(1);
        });
    }

    @Test
    void retryWaitCannotBeClaimedBeforeNextRetryAt() {
        LocalDateTime future = LocalDateTime.now(clock).plusMinutes(1).truncatedTo(ChronoUnit.MICROS);
        moveToRetryWait("retry-not-due", future);

        assertThat(stateService.claim(delivery("retry-not-due").id())).isEmpty();

        GitHubDeliveryEntity waiting = delivery("retry-not-due");
        assertThat(waiting.processingStatus()).isEqualTo("RETRY_WAIT");
        assertThat(waiting.retryCount()).isEqualTo(1);
        assertThat(waiting.nextRetryAt()).isEqualTo(future);
        assertThat(waiting.lastErrorCode()).isEqualTo("SETUP_ERROR");
        assertThat(waiting.version()).isEqualTo(2);
        assertThat(activityCount("retry-not-due")).isZero();
    }

    @Test
    void dueRetryWaitCanBeClaimed() {
        LocalDateTime due = LocalDateTime.now(clock).minusSeconds(1);
        moveToRetryWait("retry-due", due);

        GitHubDeliveryEntity processing = stateService.claim(delivery("retry-due").id()).orElseThrow();

        assertThat(processing.processingStatus()).isEqualTo("PROCESSING");
        assertThat(processing.retryCount()).isEqualTo(1);
        assertThat(processing.nextRetryAt()).isNull();
        assertThat(processing.lastErrorCode()).isEqualTo("SETUP_ERROR");
        assertThat(processing.version()).isEqualTo(3);
        assertThat(activityCount("retry-due")).isZero();
    }

    @Test
    void retryableFailureEntersRetryWaitWithFirstBackoff() {
        insertDelivery("retryable-failure", "ping", pingPayload());
        fixture.archiveProject();
        LocalDateTime beforeFailure = LocalDateTime.now(clock);

        worker.process(delivery("retryable-failure").id());

        GitHubDeliveryEntity waiting = delivery("retryable-failure");
        assertThat(waiting.processingStatus()).isEqualTo("RETRY_WAIT");
        assertThat(waiting.retryCount()).isEqualTo(1);
        assertThat(waiting.nextRetryAt()).isAfterOrEqualTo(beforeFailure.plusSeconds(10));
        assertThat(waiting.lastErrorCode()).isEqualTo("PROCESSING_ERROR");
        assertThat(waiting.lastErrorMessage()).isEqualTo("Delivery processing failed");
        assertThat(waiting.version()).isEqualTo(2);
        assertThat(activityCount("retryable-failure")).isZero();
    }

    @Test
    void retryableFailureAtRetryLimitEntersDead() {
        insertDelivery("retry-limit", "ping", pingPayload());
        setRetryCount("retry-limit", 3);
        fixture.archiveProject();

        worker.process(delivery("retry-limit").id());

        GitHubDeliveryEntity dead = delivery("retry-limit");
        assertThat(dead.processingStatus()).isEqualTo("DEAD");
        assertThat(dead.retryCount()).isEqualTo(4);
        assertThat(dead.nextRetryAt()).isNull();
        assertThat(dead.lastErrorCode()).isEqualTo("PROCESSING_ERROR");
        assertThat(dead.version()).isEqualTo(2);
        assertThat(activityCount("retry-limit")).isZero();
    }

    @Test
    void malformedAndUnsupportedDeliveriesEnterDeadWithoutRetry() {
        byte[] malformed = "{\"repository\":null}".getBytes(StandardCharsets.UTF_8);
        insertDelivery("malformed-dead", "ping", malformed);
        insertDelivery("unsupported-dead", "project", pingPayload());

        worker.process(delivery("malformed-dead").id());
        worker.process(delivery("unsupported-dead").id());

        assertTerminalFailure("malformed-dead", "GITHUB_0401");
        assertTerminalFailure("unsupported-dead", "GITHUB_0402");
    }

    @Test
    void staleProcessingIsRecoveredToRetryWait() {
        insertDelivery("stale-retry", "ping", pingPayload());
        GitHubDeliveryEntity processing = stateService.claim(delivery("stale-retry").id()).orElseThrow();
        setProcessingStartedAt("stale-retry", LocalDateTime.now(clock).minusMinutes(3));

        recoveryService.recover();

        GitHubDeliveryEntity waiting = delivery("stale-retry");
        assertThat(waiting.processingStatus()).isEqualTo("RETRY_WAIT");
        assertThat(waiting.retryCount()).isEqualTo(1);
        assertThat(waiting.nextRetryAt()).isAfter(LocalDateTime.now(clock));
        assertThat(waiting.lastErrorCode()).isEqualTo("WORKER_TIMEOUT");
        assertThat(waiting.version()).isEqualTo(processing.version() + 1);
        assertThat(activityCount("stale-retry")).isZero();
    }

    @Test
    void staleProcessingAtRetryLimitIsRecoveredToDead() {
        insertDelivery("stale-dead", "ping", pingPayload());
        setRetryCount("stale-dead", 3);
        GitHubDeliveryEntity processing = stateService.claim(delivery("stale-dead").id()).orElseThrow();
        setProcessingStartedAt("stale-dead", LocalDateTime.now(clock).minusMinutes(3));

        recoveryService.recover();

        GitHubDeliveryEntity dead = delivery("stale-dead");
        assertThat(dead.processingStatus()).isEqualTo("DEAD");
        assertThat(dead.retryCount()).isEqualTo(4);
        assertThat(dead.nextRetryAt()).isNull();
        assertThat(dead.lastErrorCode()).isEqualTo("WORKER_TIMEOUT");
        assertThat(dead.version()).isEqualTo(processing.version() + 1);
        assertThat(activityCount("stale-dead")).isZero();
    }

    @Test
    void concurrentClaimsAllowAtMostOneWorkerToOwnDelivery() throws Exception {
        insertDelivery("concurrent-claim", "ping", pingPayload());
        long id = delivery("concurrent-claim").id();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Optional<GitHubDeliveryEntity>> first = executor.submit(() -> {
                start.await();
                return stateService.claim(id);
            });
            Future<Optional<GitHubDeliveryEntity>> second = executor.submit(() -> {
                start.await();
                return stateService.claim(id);
            });

            start.countDown();
            List<Optional<GitHubDeliveryEntity>> results = List.of(first.get(), second.get());

            assertThat(results.stream().filter(Optional::isPresent).count()).isEqualTo(1);
            GitHubDeliveryEntity processing = delivery("concurrent-claim");
            assertThat(processing.processingStatus()).isEqualTo("PROCESSING");
            assertThat(processing.retryCount()).isZero();
            assertThat(processing.version()).isEqualTo(1);
            assertThat(activityCount("concurrent-claim")).isZero();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void oldWorkerVersionCannotOverwriteNewProcessingState() {
        insertDelivery("old-version", "ping", pingPayload());
        GitHubDeliveryEntity oldWorker = stateService.claim(delivery("old-version").id()).orElseThrow();
        GitHubDeliveryFailureClassifier.Classification retryableFailure =
                new GitHubDeliveryFailureClassifier.Classification(
                        true, "PROCESSING_ERROR", "Delivery processing failed"
                );
        assertThat(stateService.handleFailure(oldWorker, retryableFailure))
                .contains(GitHubDeliveryStatus.RETRY_WAIT);
        makeRetryDue("old-version");
        GitHubDeliveryEntity newWorker = stateService.claim(oldWorker.id()).orElseThrow();

        int staleSuccess = deliveryMapper.markSucceeded(
                oldWorker.id(), oldWorker.version(), LocalDateTime.now(clock)
        );
        int staleRetry = deliveryMapper.markRetryWait(
                oldWorker.id(),
                oldWorker.version(),
                LocalDateTime.now(clock).plusSeconds(10),
                "STALE_ERROR",
                "Stale worker"
        );

        assertThat(staleSuccess).isZero();
        assertThat(staleRetry).isZero();
        GitHubDeliveryEntity current = delivery("old-version");
        assertThat(current.processingStatus()).isEqualTo("PROCESSING");
        assertThat(current.retryCount()).isEqualTo(1);
        assertThat(current.lastErrorCode()).isEqualTo("PROCESSING_ERROR");
        assertThat(current.version()).isEqualTo(newWorker.version());
        assertThat(activityCount("old-version")).isZero();
    }

    @Test
    void repeatedRecoveryScansDoNotCreateSecondActivity() {
        insertDelivery("repeated-scan", "ping", pingPayload());

        recoveryService.recover();
        recoveryService.recover();

        await().untilAsserted(() -> {
            assertThat(delivery("repeated-scan").processingStatus()).isEqualTo("SUCCEEDED");
            assertThat(activityCount("repeated-scan")).isEqualTo(1);
        });
        recoveryService.recover();
        assertThat(activityCount("repeated-scan")).isEqualTo(1);
    }

    @Test
    void succeededAndDeadDeliveriesCannotBeAutomaticallyClaimedAgain() {
        insertDelivery("terminal-success", "ping", pingPayload());
        insertDelivery("terminal-dead", "project", pingPayload());
        worker.process(delivery("terminal-success").id());
        worker.process(delivery("terminal-dead").id());

        GitHubDeliveryEntity succeeded = delivery("terminal-success");
        GitHubDeliveryEntity dead = delivery("terminal-dead");
        assertThat(succeeded.processingStatus()).isEqualTo("SUCCEEDED");
        assertThat(succeeded.retryCount()).isZero();
        assertThat(succeeded.version()).isEqualTo(2);
        assertThat(dead.processingStatus()).isEqualTo("DEAD");
        assertThat(dead.retryCount()).isEqualTo(1);
        assertThat(dead.lastErrorCode()).isEqualTo("GITHUB_0402");
        assertThat(dead.version()).isEqualTo(2);
        assertThat(stateService.claim(succeeded.id())).isEmpty();
        assertThat(stateService.claim(dead.id())).isEmpty();
        assertThat(activityCount("terminal-success")).isEqualTo(1);
        assertThat(activityCount("terminal-dead")).isZero();
    }

    private void moveToRetryWait(String deliveryId, LocalDateTime nextRetryAt) {
        insertDelivery(deliveryId, "ping", pingPayload());
        GitHubDeliveryEntity processing = stateService.claim(delivery(deliveryId).id()).orElseThrow();
        int updated = deliveryMapper.markRetryWait(
                processing.id(), processing.version(), nextRetryAt, "SETUP_ERROR", "Retry setup"
        );
        assertThat(updated).isEqualTo(1);
    }

    private void assertTerminalFailure(String deliveryId, String errorCode) {
        GitHubDeliveryEntity dead = delivery(deliveryId);
        assertThat(dead.processingStatus()).isEqualTo("DEAD");
        assertThat(dead.retryCount()).isEqualTo(1);
        assertThat(dead.nextRetryAt()).isNull();
        assertThat(dead.lastErrorCode()).isEqualTo(errorCode);
        assertThat(dead.version()).isEqualTo(2);
        assertThat(activityCount(deliveryId)).isZero();
    }

    private void insertDelivery(String deliveryId, String eventType, byte[] payload) {
        int inserted = deliveryMapper.insertReceived(
                WebhookTestFixture.WORKSPACE_ID,
                WebhookTestFixture.PROJECT_ID,
                WebhookTestFixture.REPOSITORY_ID,
                deliveryId,
                eventType,
                null,
                new String(payload, StandardCharsets.UTF_8),
                "0".repeat(64),
                LocalDateTime.now(clock)
        );
        assertThat(inserted).isEqualTo(1);
    }

    private void setRetryCount(String deliveryId, int retryCount) {
        jdbcTemplate.update(
                "UPDATE dp_github_delivery SET retry_count = ? WHERE github_delivery_id = ?",
                retryCount,
                deliveryId
        );
    }

    private void setProcessingStartedAt(String deliveryId, LocalDateTime processingStartedAt) {
        jdbcTemplate.update(
                "UPDATE dp_github_delivery SET processing_started_at = ? WHERE github_delivery_id = ?",
                processingStartedAt,
                deliveryId
        );
    }

    private void makeRetryDue(String deliveryId) {
        jdbcTemplate.update(
                "UPDATE dp_github_delivery SET next_retry_at = ? WHERE github_delivery_id = ?",
                LocalDateTime.now(clock).minusSeconds(1),
                deliveryId
        );
    }

    private GitHubDeliveryEntity delivery(String deliveryId) {
        return deliveryMapper.findByGitHubDeliveryId(deliveryId).orElseThrow();
    }

    private int activityCount(String deliveryId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_project_activity WHERE source_delivery_id = ?",
                Integer.class,
                deliveryId
        );
        return count == null ? 0 : count;
    }

    private byte[] pingPayload() {
        try {
            return new ClassPathResource("webhooks/ping.json").getContentAsByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load ping payload", exception);
        }
    }
}
