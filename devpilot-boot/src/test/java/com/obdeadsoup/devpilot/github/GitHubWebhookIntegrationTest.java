package com.obdeadsoup.devpilot.github;

import com.obdeadsoup.devpilot.github.application.GitHubDeliveryProcessingService;
import com.obdeadsoup.devpilot.github.application.GitHubDeliveryStateService;
import com.obdeadsoup.devpilot.github.application.GitHubDeliveryWorker;
import com.obdeadsoup.devpilot.github.application.GitHubCommitApplicationService;
import com.obdeadsoup.devpilot.github.application.GitHubCommitReconciliationService;
import com.obdeadsoup.devpilot.github.application.GitHubSyncCheckpointService;
import com.obdeadsoup.devpilot.github.application.GitHubSyncRunStateService;
import com.obdeadsoup.devpilot.github.application.command.UpsertGitHubCommitCommand;
import com.obdeadsoup.devpilot.github.domain.GitHubCommitSource;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncTriggerType;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncCheckpointEntity;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncCheckpointMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncRunMapper;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class GitHubWebhookIntegrationTest {

    private static final String TEST_SECRET = "integration-test-secret";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_test")
            .withUsername("devpilot")
            .withPassword("devpilot_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add(WebhookTestFixture.SECRET_REFERENCE, () -> TEST_SECRET);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private GitHubDeliveryMapper deliveryMapper;

    @Autowired
    private GitHubDeliveryStateService stateService;

    @Autowired
    private GitHubDeliveryProcessingService processingService;

    @Autowired
    private GitHubDeliveryWorker worker;

    @Autowired
    private GitHubCommitApplicationService commitApplicationService;

    @Autowired
    private GitHubSyncCheckpointService checkpointService;

    @Autowired
    private GitHubSyncRunStateService syncRunStateService;

    @Autowired
    private GitHubCommitReconciliationService reconciliationService;

    @Autowired
    private GitHubSyncCheckpointMapper checkpointMapper;

    @Autowired
    private GitHubSyncRunMapper syncRunMapper;

    private WebhookTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new WebhookTestFixture(jdbcTemplate);
        fixture.reset();
        fixture.createActiveBinding();
    }

    @Test
    void flywayCreatesWebhookAndSnapshotTablesWithCoreIndexes() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                    'dp_workspace', 'dp_project', 'dp_github_repository',
                    'dp_github_delivery', 'dp_project_activity',
                    'dp_github_issue', 'dp_github_pull_request',
                    'dp_github_pull_request_review'
                  )
                """, Integer.class);

        assertThat(count).isEqualTo(8);

        Integer indexColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'dp_github_delivery'
                  AND index_name = 'idx_github_delivery_processing_scan'
                """, Integer.class);
        assertThat(indexColumns).isEqualTo(2);

        Integer uniqueSnapshotIndexes = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT table_name, index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND non_unique = 0
                  AND index_name IN (
                    'uk_github_issue_repository_id', 'uk_github_issue_repository_number',
                    'uk_github_pr_repository_id', 'uk_github_pr_repository_number',
                    'uk_github_review_repository_id'
                  )
                """, Integer.class);
        assertThat(uniqueSnapshotIndexes).isEqualTo(5);
    }

    @Test
    void databaseEnforcesRepositoryUniquenessAndWorkspaceProjectOwnership() {
        fixture.createSecondWorkspaceAndProject();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO dp_github_repository (
                    workspace_id, project_id, github_repository_id, owner_login,
                    repository_name, full_name, binding_status, webhook_secret_ref
                ) VALUES (101, 201, 123456, 'duplicate', 'repo', 'duplicate/repo', 'ACTIVE', ?)
                """, WebhookTestFixture.SECRET_REFERENCE)).isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO dp_github_repository (
                    workspace_id, project_id, github_repository_id, owner_login,
                    repository_name, full_name, binding_status, webhook_secret_ref
                ) VALUES (101, 200, 999999, 'wrong', 'scope', 'wrong/scope', 'ACTIVE', ?)
                """, WebhookTestFixture.SECRET_REFERENCE)).isInstanceOf(RuntimeException.class);
    }

    @Test
    void rejectsMissingHeadersAndWrongSignature() throws Exception {
        byte[] ping = payload("webhooks/ping.json");

        mockMvc.perform(post("/api/v1/github/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(ping))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GITHUB_0400"));

        mockMvc.perform(post("/api/v1/github/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signature(ping))
                        .header("X-GitHub-Event", "ping")
                        .content(ping))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GITHUB_0400"));

        mockMvc.perform(post("/api/v1/github/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .header("X-Hub-Signature-256", signature(ping))
                        .header("X-GitHub-Delivery", "delivery-missing-event")
                        .content(ping))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GITHUB_0400"));

        mockMvc.perform(webhook(ping, "delivery-wrong-signature", "ping", "sha256=" + "0".repeat(64)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("GITHUB_0404"));
    }

    @Test
    void rejectsEmptyPayloadWithExplicitClientError() throws Exception {
        byte[] empty = new byte[0];

        mockMvc.perform(webhook(empty, "delivery-empty", "ping", signature(empty)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GITHUB_0401"));
    }

    @Test
    void rejectsUnknownOrDisabledRepository() throws Exception {
        byte[] unknown = """
                {"repository":{"id":999999,"full_name":"unknown/repo"}}
                """.getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(webhook(unknown, "delivery-unknown", "ping", signature(unknown)))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GITHUB_0405"));

        fixture.disableBinding();
        byte[] ping = payload("webhooks/ping.json");
        mockMvc.perform(webhook(ping, "delivery-disabled", "ping", signature(ping)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GITHUB_0406"));
    }

    @Test
    void acceptsPingAndProcessesDuplicateDeliveryOnlyOnce() throws Exception {
        byte[] ping = payload("webhooks/ping.json");

        mockMvc.perform(webhook(ping, "delivery-ping", "ping", signature(ping)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.duplicate").value(false));

        await().untilAsserted(() -> {
            assertThat(deliveryStatus("delivery-ping")).isEqualTo("SUCCEEDED");
            assertThat(activityCount("delivery-ping")).isEqualTo(1);
            assertThat(activityType("delivery-ping")).isEqualTo("GITHUB_WEBHOOK_PING");
        });

        mockMvc.perform(webhook(ping, "delivery-ping", "ping", signature(ping)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.duplicate").value(true));

        assertThat(activityCount("delivery-ping")).isEqualTo(1);
    }

    @Test
    void rejectsDuplicateDeliveryWhenPayloadDiffersWithoutOverwritingOriginal() throws Exception {
        byte[] originalPing = payload("webhooks/ping.json");
        byte[] changedPing = new String(originalPing, StandardCharsets.UTF_8)
                .replace("\"login\": \"octocat\"", "\"login\": \"different-sender\"")
                .getBytes(StandardCharsets.UTF_8);

        mockMvc.perform(webhook(
                        originalPing,
                        "delivery-payload-conflict",
                        "ping",
                        signature(originalPing)
                ))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.duplicate").value(false));

        await().untilAsserted(() -> {
            assertThat(deliveryStatus("delivery-payload-conflict")).isEqualTo("SUCCEEDED");
            assertThat(activityCount("delivery-payload-conflict")).isEqualTo(1);
        });

        mockMvc.perform(webhook(
                        changedPing,
                        "delivery-payload-conflict",
                        "ping",
                        signature(changedPing)
                ))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("GITHUB_0502"));

        assertThat(deliveryCount("delivery-payload-conflict")).isEqualTo(1);
        assertThat(deliveryPayloadSha256("delivery-payload-conflict")).isEqualTo(sha256(originalPing));
        assertThat(deliverySenderLogin("delivery-payload-conflict")).isEqualTo("octocat");
        assertThat(activityCount("delivery-payload-conflict")).isEqualTo(1);
    }

    @Test
    void parsesPushAndReturnsScopedPaginatedTimeline() throws Exception {
        byte[] push = payload("webhooks/push.json");

        mockMvc.perform(webhook(push, "delivery-push", "push", signature(push)))
                .andExpect(status().isAccepted());

        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-push")).isEqualTo("SUCCEEDED"));

        mockMvc.perform(get("/api/v1/workspaces/100/projects/200/activities")
                        .with(authentication(ownerAuthentication()))
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(3));

        assertThat(githubCommitCount()).isEqualTo(2);
        assertThat(activityTypeCount("CODE_PUSHED")).isEqualTo(1);
        assertThat(activityTypeCount("GITHUB_COMMIT_DISCOVERED")).isEqualTo(2);

        fixture.createSecondWorkspaceAndProject();
        mockMvc.perform(get("/api/v1/workspaces/100/projects/201/activities")
                        .with(authentication(ownerAuthentication())))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));
    }

    @Test
    void conditionallyClaimsAndTransitionsDeliveryToSucceeded() {
        byte[] ping = payload("webhooks/ping.json");
        insertDelivery("delivery-state", "ping", ping);

        GitHubDeliveryEntity received = deliveryMapper.findByGitHubDeliveryId("delivery-state").orElseThrow();
        assertThat(received.processingStatus()).isEqualTo("RECEIVED");

        GitHubDeliveryEntity processing = stateService.claim(received.id()).orElseThrow();
        assertThat(processing.processingStatus()).isEqualTo("PROCESSING");
        assertThat(stateService.claim(received.id())).isEmpty();

        processingService.process(processing);
        assertThat(deliveryStatus("delivery-state")).isEqualTo("SUCCEEDED");
        assertThat(activityCount("delivery-state")).isEqualTo(1);
    }

    @Test
    void unsupportedEventMovesDeliveryDirectlyToDeadWithoutLeakingPayload() {
        byte[] ping = payload("webhooks/ping.json");
        insertDelivery("delivery-failed", "project", ping);
        long id = deliveryMapper.findByGitHubDeliveryId("delivery-failed").orElseThrow().id();

        worker.process(id);

        GitHubDeliveryEntity dead = deliveryMapper.findById(id).orElseThrow();
        assertThat(dead.processingStatus()).isEqualTo("DEAD");
        assertThat(dead.retryCount()).isEqualTo(1);
        assertThat(dead.nextRetryAt()).isNull();
        assertThat(dead.lastErrorCode()).isEqualTo("GITHUB_0402");
        assertThat(dead.lastErrorMessage()).isEqualTo("Unsupported GitHub webhook event");
        assertThat(dead.version()).isEqualTo(2);
        assertThat(activityCount("delivery-failed")).isZero();
    }

    @Test
    void issueWebhookUsesOneSnapshotRejectsOlderUpdateAndExposesScopedReadApi() throws Exception {
        byte[] opened = issuePayload("opened", "open", "Initial title", "2026-08-01T01:00:00Z");
        mockMvc.perform(webhook(opened, "delivery-issue-opened", "issues", signature(opened)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-issue-opened")).isEqualTo("SUCCEEDED"));

        byte[] edited = issuePayload("edited", "open", "Newest title", "2026-08-01T03:00:00Z");
        mockMvc.perform(webhook(edited, "delivery-issue-edited", "issues", signature(edited)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-issue-edited")).isEqualTo("SUCCEEDED"));

        byte[] closed = issuePayload("closed", "closed", "Newest title", "2026-08-01T04:00:00Z");
        mockMvc.perform(webhook(closed, "delivery-issue-closed", "issues", signature(closed)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-issue-closed")).isEqualTo("SUCCEEDED"));
        byte[] reopened = issuePayload("reopened", "open", "Newest title", "2026-08-01T05:00:00Z");
        mockMvc.perform(webhook(reopened, "delivery-issue-reopened", "issues", signature(reopened)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-issue-reopened")).isEqualTo("SUCCEEDED"));
        byte[] older = issuePayload("closed", "closed", "Older title", "2026-08-01T02:00:00Z");
        mockMvc.perform(webhook(older, "delivery-issue-old", "issues", signature(older)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-issue-old")).isEqualTo("SUCCEEDED"));

        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dp_github_issue", Integer.class)).isEqualTo(1);
        assertThat(jdbcTemplate.queryForObject("SELECT title FROM dp_github_issue", String.class)).isEqualTo("Newest title");
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM dp_github_issue", String.class)).isEqualTo("OPEN");
        assertThat(activityCount("delivery-issue-old")).isZero();

        mockMvc.perform(get("/api/v1/workspaces/100/projects/200/github/issues")
                        .with(authentication(ownerAuthentication())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].body").doesNotExist())
                .andExpect(jsonPath("$.data.items[0].externalUntrustedContent").value(true));

        mockMvc.perform(get("/api/v1/workspaces/100/projects/200/github/issues"))
                .andExpect(status().isUnauthorized());
        fixture.createSecondWorkspaceAndProject();
        mockMvc.perform(get("/api/v1/workspaces/101/projects/201/github/issues")
                        .with(authentication(ownerAuthentication())))
                .andExpect(status().isForbidden());
    }

    @Test
    void pullRequestAndReviewWebhookUseSeparateStableIdsAndOneActivityPerDelivery() throws Exception {
        byte[] pull = pullRequestPayload("opened", "open", true, null, "2026-08-01T01:00:00Z");
        mockMvc.perform(webhook(pull, "delivery-pr-opened", "pull_request", signature(pull)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-pr-opened")).isEqualTo("SUCCEEDED"));

        byte[] ready = pullRequestPayload("ready_for_review", "open", false, null, "2026-08-01T02:00:00Z");
        mockMvc.perform(webhook(ready, "delivery-pr-ready", "pull_request", signature(ready))).andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-pr-ready")).isEqualTo("SUCCEEDED"));
        byte[] synchronizedPull = new String(pullRequestPayload("synchronize", "open", false, null,
                "2026-08-01T03:00:00Z"), StandardCharsets.UTF_8).replace("a".repeat(40), "c".repeat(40))
                .getBytes(StandardCharsets.UTF_8);
        mockMvc.perform(webhook(synchronizedPull, "delivery-pr-sync", "pull_request", signature(synchronizedPull)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-pr-sync")).isEqualTo("SUCCEEDED"));
        byte[] closed = pullRequestPayload("closed", "closed", false, null, "2026-08-01T04:00:00Z");
        mockMvc.perform(webhook(closed, "delivery-pr-closed", "pull_request", signature(closed))).andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-pr-closed")).isEqualTo("SUCCEEDED"));
        byte[] merged = pullRequestPayload("closed", "closed", false, "2026-08-01T05:00:00Z", "2026-08-01T05:00:00Z");
        mockMvc.perform(webhook(merged, "delivery-pr-merged", "pull_request", signature(merged))).andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-pr-merged")).isEqualTo("SUCCEEDED"));

        byte[] review = reviewPayload("submitted", "approved", "2026-08-01T02:00:00Z");
        mockMvc.perform(webhook(review, "delivery-review-approved", "pull_request_review", signature(review)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-review-approved")).isEqualTo("SUCCEEDED"));
        byte[] changes = reviewPayload("edited", "changes_requested", "2026-08-01T03:00:00Z");
        mockMvc.perform(webhook(changes, "delivery-review-changes", "pull_request_review", signature(changes)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-review-changes")).isEqualTo("SUCCEEDED"));
        byte[] dismissed = reviewPayload("dismissed", "dismissed", "2026-08-01T04:00:00Z");
        mockMvc.perform(webhook(dismissed, "delivery-review-dismissed", "pull_request_review", signature(dismissed)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-review-dismissed")).isEqualTo("SUCCEEDED"));

        assertThat(jdbcTemplate.queryForObject("SELECT github_pull_request_id FROM dp_github_pull_request", Long.class))
                .isEqualTo(701L);
        assertThat(jdbcTemplate.queryForObject("SELECT status FROM dp_github_pull_request", String.class)).isEqualTo("MERGED");
        assertThat(jdbcTemplate.queryForObject("SELECT github_review_id FROM dp_github_pull_request_review", Long.class))
                .isEqualTo(901L);
        assertThat(jdbcTemplate.queryForObject("SELECT state FROM dp_github_pull_request_review", String.class)).isEqualTo("DISMISSED");
        assertThat(activityCount("delivery-pr-opened")).isEqualTo(1);
        assertThat(activityCount("delivery-review-approved")).isEqualTo(1);
    }

    @Test
    void unsupportedSnapshotActionSucceedsWithoutCreatingSnapshotOrActivity() throws Exception {
        byte[] payload = issuePayload("milestoned", "open", "Ignored", "2026-08-01T01:00:00Z");
        mockMvc.perform(webhook(payload, "delivery-issue-unsupported", "issues", signature(payload)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-issue-unsupported")).isEqualTo("SUCCEEDED"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dp_github_issue", Integer.class)).isZero();
        assertThat(activityCount("delivery-issue-unsupported")).isZero();
    }

    @Test
    void apiFindingWebhookCommitDoesNotDuplicateCommitOrActivity() throws Exception {
        byte[] push = payload("webhooks/push.json");
        mockMvc.perform(webhook(push, "delivery-webhook-first", "push", signature(push)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-webhook-first"))
                .isEqualTo("SUCCEEDED"));

        commitApplicationService.upsert(apiCommit("1111111111111111111111111111111111111112"));

        assertThat(githubCommitCount()).isEqualTo(2);
        assertThat(commitActivityCount("1111111111111111111111111111111111111112")).isEqualTo(1);
        assertThat(commitFirstSource("1111111111111111111111111111111111111112"))
                .isEqualTo("WEBHOOK");
    }

    @Test
    void webhookFindingApiCommitKeepsFirstSourceAndDoesNotDuplicateActivity() throws Exception {
        String sha = "2222222222222222222222222222222222222222";
        commitApplicationService.upsert(apiCommit(sha));

        byte[] push = payload("webhooks/push.json");
        mockMvc.perform(webhook(push, "delivery-api-first", "push", signature(push)))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> assertThat(deliveryStatus("delivery-api-first"))
                .isEqualTo("SUCCEEDED"));

        assertThat(githubCommitCount()).isEqualTo(2);
        assertThat(commitActivityCount(sha)).isEqualTo(1);
        assertThat(commitFirstSource(sha)).isEqualTo("API");
        assertThat(activityTypeCount("CODE_PUSHED")).isEqualTo(1);
    }

    @Test
    void concurrentUpsertCreatesAtMostOneCommitAndOneActivity() throws Exception {
        String sha = "c".repeat(40);
        try (var executor = Executors.newFixedThreadPool(6)) {
            List<Callable<Void>> calls = new ArrayList<>();
            for (int index = 0; index < 12; index++) {
                calls.add(() -> {
                    commitApplicationService.upsert(apiCommit(sha));
                    return null;
                });
            }
            List<Future<Void>> futures = executor.invokeAll(calls);
            for (Future<Void> future : futures) {
                future.get();
            }
        }

        assertThat(commitCount(sha)).isEqualTo(1);
        assertThat(commitActivityCount(sha)).isEqualTo(1);
    }

    @Test
    void checkpointAndSucceededRunAdvanceAtomicallyWithVersionClaim() {
        GitHubSyncCheckpointEntity checkpoint = checkpointService.getOrCreate(WebhookTestFixture.REPOSITORY_ID);
        String sha = "d".repeat(40);
        GitHubSyncCheckpointEntity progressed = checkpointService.recordPage(checkpoint, sha);
        GitHubSyncRunEntity pending = syncRunStateService.createOrGetOpen(
                WebhookTestFixture.REPOSITORY_ID, GitHubSyncTriggerType.INITIAL, null
        ).run();
        GitHubSyncRunEntity running = syncRunStateService.claim(pending.id()).orElseThrow();
        LocalDateTime boundary = LocalDateTime.of(2026, 8, 1, 10, 30);

        syncRunStateService.complete(running, progressed, boundary, sha);

        GitHubSyncCheckpointEntity completed = checkpointMapper
                .findCommitCheckpoint(WebhookTestFixture.REPOSITORY_ID).orElseThrow();
        GitHubSyncRunEntity succeeded = syncRunMapper.findById(running.id()).orElseThrow();
        assertThat(completed.lastSuccessfulSyncAt()).isEqualTo(boundary);
        assertThat(completed.lastSeenCommitSha()).isEqualTo(sha);
        assertThat(succeeded.status()).isEqualTo("SUCCEEDED");
        assertThat(checkpointMapper.updatePageProgress(completed.id(), progressed.version(), sha)).isZero();
    }

    @Test
    void twoDatabaseWorkersCanClaimOnlyOneSyncRun() throws Exception {
        GitHubSyncRunEntity pending = syncRunStateService.createOrGetOpen(
                WebhookTestFixture.REPOSITORY_ID, GitHubSyncTriggerType.SCHEDULED, null
        ).run();
        try (var executor = Executors.newFixedThreadPool(2)) {
            List<Future<Boolean>> claims = executor.invokeAll(List.of(
                    () -> syncRunStateService.claim(pending.id()).isPresent(),
                    () -> syncRunStateService.claim(pending.id()).isPresent()
            ));

            assertThat(claims.stream().filter(future -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new IllegalStateException(exception);
                }
            }).count()).isEqualTo(1);
        }
    }

    @Test
    void disabledBindingAndArchivedProjectRunsBecomeDeadWithoutApiCall() {
        fixture.disableBinding();
        GitHubSyncRunEntity disabledRun = syncRunStateService.createOrGetOpen(
                WebhookTestFixture.REPOSITORY_ID, GitHubSyncTriggerType.SCHEDULED, null
        ).run();
        reconciliationService.reconcile(disabledRun.id());
        assertThat(syncRunMapper.findById(disabledRun.id()).orElseThrow().status()).isEqualTo("DEAD");

        fixture.reset();
        fixture.createActiveBinding();
        fixture.archiveProject();
        GitHubSyncRunEntity archivedRun = syncRunStateService.createOrGetOpen(
                WebhookTestFixture.REPOSITORY_ID, GitHubSyncTriggerType.SCHEDULED, null
        ).run();
        reconciliationService.reconcile(archivedRun.id());
        assertThat(syncRunMapper.findById(archivedRun.id()).orElseThrow().status()).isEqualTo("DEAD");
    }

    @Test
    void manualSyncReturns202AndRequiresRepositoryUpdatePermission() throws Exception {
        mockMvc.perform(post(
                        "/api/v1/workspaces/100/projects/200/github-repositories/300/sync/commits"
                ).with(authentication(ownerAuthentication())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.runId").isNumber());

        await().untilAsserted(() -> assertThat(openSyncRunCount()).isZero());

        mockMvc.perform(post(
                        "/api/v1/workspaces/100/projects/200/github-repositories/300/sync/commits"
                ).with(authentication(userAuthentication(
                        WebhookTestFixture.SECOND_OWNER_USER_ID,
                        "outsider",
                        "outsider@example.com"
                ))))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webhook(
            byte[] payload,
            String deliveryId,
            String event,
            String signature
    ) {
        return post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", signature)
                .header("X-GitHub-Delivery", deliveryId)
                .header("X-GitHub-Event", event)
                .content(payload);
    }

    private byte[] payload(String path) {
        try {
            return new ClassPathResource(path).getContentAsByteArray();
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot load test payload", exception);
        }
    }

    private byte[] issuePayload(String action, String state, String title, String updatedAt) {
        return """
                {"action":"%s","repository":{"id":123456},"issue":{"id":501,"number":12,
                "title":"%s","body":"External markdown","state":"%s","user":{"id":7,"login":"octo"},
                "assignees":[],"labels":[],"html_url":"https://github.com/octo-org/devpilot/issues/12",
                "created_at":"2026-08-01T00:00:00Z","updated_at":"%s"}}
                """.formatted(action, title, state, updatedAt).getBytes(StandardCharsets.UTF_8);
    }

    private byte[] pullRequestPayload(String action, String state, boolean draft, String mergedAt, String updatedAt) {
        return """
                {"action":"%s","repository":{"id":123456},"pull_request":{"id":701,"number":22,"title":"PR title",
                "body":"External PR markdown","state":"%s","draft":%s,"merged":%s,"user":{"id":8,"login":"dev"},
                "head":{"ref":"feature","sha":"%s"},"base":{"ref":"main","sha":"%s"},
                "requested_reviewers":[],"assignees":[],"labels":[],"html_url":"https://github.com/octo-org/devpilot/pull/22",
                "created_at":"2026-08-01T00:00:00Z","updated_at":"%s"%s}}
                """.formatted(action, state, draft, mergedAt != null, "a".repeat(40), "b".repeat(40), updatedAt,
                mergedAt == null ? "" : ",\"merged_at\":\"" + mergedAt + "\"").getBytes(StandardCharsets.UTF_8);
    }

    private byte[] reviewPayload(String action, String state, String submittedAt) {
        String pull = new String(pullRequestPayload("opened", "open", false, null,
                "2026-08-01T01:00:00Z"), StandardCharsets.UTF_8);
        String prObject = pull.substring(pull.indexOf("{\"id\":701"), pull.length() - 2);
        return ("{\"action\":\"" + action + "\",\"repository\":{\"id\":123456},\"pull_request\":"
                + prObject + ",\"review\":{\"id\":901,\"state\":\"" + state
                + "\",\"body\":\"review\",\"commit_id\":\"" + "a".repeat(40)
                + "\",\"user\":{\"id\":9,\"login\":\"reviewer\"},\"html_url\":"
                + "\"https://github.com/octo-org/devpilot/pull/22#pullrequestreview-901\",\"submitted_at\":\""
                + submittedAt + "\"}}").getBytes(StandardCharsets.UTF_8);
    }

    private String signature(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(TEST_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign test payload", exception);
        }
    }

    private String sha256(byte[] payload) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot hash test payload", exception);
        }
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
                LocalDateTime.of(2026, 7, 21, 12, 0)
        );
        assertThat(inserted).isEqualTo(1);
    }

    private String deliveryStatus(String deliveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT processing_status FROM dp_github_delivery WHERE github_delivery_id = ?",
                String.class,
                deliveryId
        );
    }

    private int activityCount(String deliveryId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_project_activity WHERE source_delivery_id = ?",
                Integer.class,
                deliveryId
        );
        return count == null ? 0 : count;
    }

    private int deliveryCount(String deliveryId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_github_delivery WHERE github_delivery_id = ?",
                Integer.class,
                deliveryId
        );
        return count == null ? 0 : count;
    }

    private String deliveryPayloadSha256(String deliveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT payload_sha256 FROM dp_github_delivery WHERE github_delivery_id = ?",
                String.class,
                deliveryId
        );
    }

    private String deliverySenderLogin(String deliveryId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT JSON_UNQUOTE(JSON_EXTRACT(payload_json, '$.sender.login'))
                        FROM dp_github_delivery
                        WHERE github_delivery_id = ?
                        """,
                String.class,
                deliveryId
        );
    }

    private String activityType(String deliveryId) {
        return jdbcTemplate.queryForObject(
                "SELECT activity_type FROM dp_project_activity WHERE source_delivery_id = ?",
                String.class,
                deliveryId
        );
    }

    private int githubCommitCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dp_github_commit", Integer.class);
        return count == null ? 0 : count;
    }

    private int activityTypeCount(String activityType) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_project_activity WHERE activity_type = ?",
                Integer.class,
                activityType
        );
        return count == null ? 0 : count;
    }

    private UpsertGitHubCommitCommand apiCommit(String sha) {
        return new UpsertGitHubCommitCommand(
                WebhookTestFixture.WORKSPACE_ID,
                WebhookTestFixture.PROJECT_ID,
                WebhookTestFixture.REPOSITORY_ID,
                WebhookTestFixture.GITHUB_REPOSITORY_ID,
                "octo-org/devpilot",
                sha,
                "API reconciliation commit",
                "Octo Cat",
                "private@example.com",
                7L,
                "octocat",
                LocalDateTime.of(2026, 8, 1, 10, 0),
                "https://github.com/octo-org/devpilot/commit/" + sha,
                GitHubCommitSource.API
        );
    }

    private int commitCount(String sha) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_github_commit WHERE commit_sha = ?", Integer.class, sha
        );
        return count == null ? 0 : count;
    }

    private int commitActivityCount(String sha) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_project_activity WHERE source_delivery_id = ?",
                Integer.class,
                "commit:" + WebhookTestFixture.GITHUB_REPOSITORY_ID + ":" + sha
        );
        return count == null ? 0 : count;
    }

    private String commitFirstSource(String sha) {
        return jdbcTemplate.queryForObject(
                "SELECT first_seen_source FROM dp_github_commit WHERE commit_sha = ?",
                String.class,
                sha
        );
    }

    private int openSyncRunCount() {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_github_sync_run WHERE status IN ('PENDING', 'RUNNING', 'RETRY_WAIT')",
                Integer.class
        );
        return count == null ? 0 : count;
    }

    private org.springframework.security.core.Authentication ownerAuthentication() {
        return userAuthentication(
                WebhookTestFixture.OWNER_USER_ID,
                "webhook-owner",
                "webhook-owner@example.com"
        );
    }

    private org.springframework.security.core.Authentication userAuthentication(
            long userId,
            String username,
            String email
    ) {
        DevPilotUserPrincipal principal = new DevPilotUserPrincipal(
                userId, username, email, "Webhook Test User"
        );
        return new org.springframework.security.authentication.UsernamePasswordAuthenticationToken(
                principal,
                null,
                List.of()
        );
    }
}
