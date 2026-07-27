package com.obdeadsoup.devpilot.github;

import com.obdeadsoup.devpilot.github.application.GitHubDeliveryProcessingService;
import com.obdeadsoup.devpilot.github.application.GitHubDeliveryStateService;
import com.obdeadsoup.devpilot.github.application.GitHubDeliveryWorker;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubDeliveryEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubDeliveryMapper;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.user;
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

    private WebhookTestFixture fixture;

    @BeforeEach
    void setUp() {
        fixture = new WebhookTestFixture(jdbcTemplate);
        fixture.reset();
        fixture.createActiveBinding();
    }

    @Test
    void flywayCreatesOnlyTheFiveVerticalSliceTables() {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE()
                  AND table_name IN (
                    'dp_workspace', 'dp_project', 'dp_github_repository',
                    'dp_github_delivery', 'dp_project_activity'
                  )
                """, Integer.class);

        assertThat(count).isEqualTo(5);
    }

    @Test
    void databaseEnforcesRepositoryUniquenessAndWorkspaceProjectOwnership() {
        fixture.createSecondWorkspaceAndProject();

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO dp_github_repository (
                    workspace_id, project_id, github_repository_id, owner_login,
                    repository_name, full_name, binding_status, credential_ref
                ) VALUES (101, 201, 123456, 'duplicate', 'repo', 'duplicate/repo', 'ACTIVE', ?)
                """, WebhookTestFixture.SECRET_REFERENCE)).isInstanceOf(RuntimeException.class);

        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO dp_github_repository (
                    workspace_id, project_id, github_repository_id, owner_login,
                    repository_name, full_name, binding_status, credential_ref
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
                        .with(user("timeline-reader"))
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].activityType").value("CODE_PUSHED"))
                .andExpect(jsonPath("$.data.items[0].repositoryFullName").value("octo-org/devpilot"))
                .andExpect(jsonPath("$.data.items[0].actorLogin").value("octocat"))
                .andExpect(jsonPath("$.data.items[0].gitRef").value("refs/heads/main"))
                .andExpect(jsonPath("$.data.items[0].commitCount").value(2))
                .andExpect(jsonPath("$.data.items[0].headCommitMessage")
                        .value("Implement webhook vertical slice"));

        fixture.createSecondWorkspaceAndProject();
        mockMvc.perform(get("/api/v1/workspaces/100/projects/201/activities")
                        .with(user("timeline-reader")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PROJECT_0404"));
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
    void processingFailureMovesDeliveryToFailedWithoutLeakingPayload() {
        byte[] ping = payload("webhooks/ping.json");
        insertDelivery("delivery-failed", "issues", ping);
        long id = deliveryMapper.findByGitHubDeliveryId("delivery-failed").orElseThrow().id();

        worker.process(id);

        assertThat(deliveryStatus("delivery-failed")).isEqualTo("FAILED");
        String storedMessage = jdbcTemplate.queryForObject(
                "SELECT last_error_message FROM dp_github_delivery WHERE id = ?",
                String.class,
                id
        );
        assertThat(storedMessage).isEqualTo("Delivery processing failed");
        assertThat(activityCount("delivery-failed")).isZero();
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
}
