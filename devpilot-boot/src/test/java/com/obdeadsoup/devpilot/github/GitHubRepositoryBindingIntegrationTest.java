package com.obdeadsoup.devpilot.github;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.github.api.dto.GitHubRepositoryResponse;
import com.obdeadsoup.devpilot.github.application.GitHubRepositoryBindingService;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiResponse;
import com.obdeadsoup.devpilot.github.application.client.GitHubConditionalRequest;
import com.obdeadsoup.devpilot.github.application.client.GitHubPageCursor;
import com.obdeadsoup.devpilot.github.application.client.GitHubRateLimitSnapshot;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.application.client.VerifiedGitHubRepository;
import com.obdeadsoup.devpilot.github.application.secret.WebhookSecretResolver;
import com.obdeadsoup.devpilot.github.error.GitHubRepositoryErrorCode;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class GitHubRepositoryBindingIntegrationTest {

    private static final long OWNER_ID = 1L;
    private static final long MEMBER_ID = 2L;
    private static final long OTHER_OWNER_ID = 3L;
    private static final long WORKSPACE_ID = 100L;
    private static final long PROJECT_ID = 200L;
    private static final long SECOND_PROJECT_ID = 201L;
    private static final long OTHER_WORKSPACE_ID = 101L;
    private static final long OTHER_PROJECT_ID = 300L;
    private static final long GITHUB_REPOSITORY_ID = 123_456L;
    private static final String API_REFERENCE = "DEVPILOT_GITHUB_API_TOKEN_TEST";
    private static final String WEBHOOK_REFERENCE = "DEVPILOT_GITHUB_WEBHOOK_SECRET_TEST";
    private static final String WEBHOOK_SECRET = "integration-webhook-secret";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_repository_binding_test")
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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private GitHubRepositoryBindingService bindingService;

    @MockitoBean
    private GitHubRepositoryMetadataClient metadataClient;

    @MockitoBean
    private WebhookSecretResolver webhookSecretResolver;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        clearData();
        reset(metadataClient, webhookSecretResolver);
        insertUser(OWNER_ID, "owner");
        insertUser(MEMBER_ID, "member");
        insertUser(OTHER_OWNER_ID, "other-owner");
        insertWorkspace(WORKSPACE_ID, "binding-workspace", OWNER_ID);
        insertWorkspace(OTHER_WORKSPACE_ID, "other-binding-workspace", OTHER_OWNER_ID);
        insertWorkspaceMember(WORKSPACE_ID, MEMBER_ID, "MEMBER");
        insertProject(PROJECT_ID, WORKSPACE_ID, "MAIN", OWNER_ID);
        insertProject(SECOND_PROJECT_ID, WORKSPACE_ID, "SECOND", OWNER_ID);
        insertProject(OTHER_PROJECT_ID, OTHER_WORKSPACE_ID, "OTHER", OTHER_OWNER_ID);
        when(webhookSecretResolver.resolve(WEBHOOK_REFERENCE))
                .thenReturn(Optional.of(WEBHOOK_SECRET));
        when(metadataClient.getRepository(
                anyString(), anyString(), eq(API_REFERENCE), any(GitHubConditionalRequest.class)
        )).thenReturn(apiResponse(repository(
                GITHUB_REPOSITORY_ID, "trusted-org", "trusted-repo"
        ), false, "\"etag-v1\""));
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void flywayV7AddsConditionalMetadataValidatorsWithoutChangingV6Indexes() {
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version IN ('6', '7') AND success = 1
                """, Integer.class);
        Integer columns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'dp_github_repository'
                  AND column_name IN (
                    'webhook_secret_ref', 'api_credential_ref', 'last_verified_at', 'created_by',
                    'active_github_repository_id', 'active_repository_full_name'
                  )
                """, Integer.class);
        Integer activeIndexes = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'dp_github_repository'
                  AND index_name IN (
                    'uk_github_repository_active_external_id',
                    'uk_github_repository_workspace_active_full_name'
                  )
                """, Integer.class);
        Integer removedIndexes = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'dp_github_repository'
                  AND index_name IN (
                    'uk_github_repository_external_id',
                    'uk_github_repository_workspace_full_name_deleted'
                  )
                """, Integer.class);

        Integer validators = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'dp_github_repository'
                  AND column_name IN ('metadata_etag', 'metadata_last_modified')
                """, Integer.class);

        assertThat(applied).isEqualTo(2);
        assertThat(columns).isEqualTo(6);
        assertThat(validators).isEqualTo(2);
        assertThat(activeIndexes).isEqualTo(2);
        assertThat(removedIndexes).isZero();
    }

    @Test
    void firstBindUsesGitHubAuthorityAndResponseNeverExposesCredentials() throws Exception {
        MvcResult result = mockMvc.perform(post(collectionPath(WORKSPACE_ID, PROJECT_ID))
                        .with(authentication(testAuthentication(OWNER_ID, "owner")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "owner", "client-owner",
                                "repositoryName", "client-repository",
                                "apiCredentialRef", API_REFERENCE,
                                "webhookSecretRef", WEBHOOK_REFERENCE
                        ))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.githubRepositoryId").value(GITHUB_REPOSITORY_ID))
                .andExpect(jsonPath("$.data.ownerLogin").value("trusted-org"))
                .andExpect(jsonPath("$.data.repositoryName").value("trusted-repo"))
                .andExpect(jsonPath("$.data.fullName").value("trusted-org/trusted-repo"))
                .andExpect(jsonPath("$.data.hasApiCredential").value(true))
                .andExpect(jsonPath("$.data.hasWebhookSecret").value(true))
                .andExpect(jsonPath("$.data.version").value(0))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .doesNotContain(API_REFERENCE, WEBHOOK_REFERENCE, WEBHOOK_SECRET, "etag-v1");
        assertThat(repositoryOwner(GITHUB_REPOSITORY_ID)).isEqualTo("trusted-org");
        assertThat(repositoryCreatedBy(GITHUB_REPOSITORY_ID)).isEqualTo(OWNER_ID);
        verify(metadataClient).getRepository(
                "client-owner", "client-repository", API_REFERENCE, GitHubConditionalRequest.none()
        );

        long bindingId = objectMapper.readTree(result.getResponse().getContentAsByteArray())
                .path("data").path("id").asLong();
        mockMvc.perform(get(collectionPath(WORKSPACE_ID, PROJECT_ID))
                        .with(authentication(testAuthentication(OWNER_ID, "owner")))
                        .param("status", "ACTIVE")
                        .param("page", "1")
                        .param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(bindingId));
        mockMvc.perform(get(itemPath(WORKSPACE_ID, PROJECT_ID, bindingId))
                        .with(authentication(testAuthentication(OWNER_ID, "owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.fullName").value("trusted-org/trusted-repo"));
    }

    @Test
    void sameRepositoryCannotBindTwoProjectsAndConcurrentBindHasAtMostOneWinner() throws Exception {
        authenticate(OWNER_ID, "owner");
        bindingService.bindRepository(
                WORKSPACE_ID, PROJECT_ID, "octo", "demo", API_REFERENCE, WEBHOOK_REFERENCE
        );

        assertThatThrownBy(() -> bindingService.bindRepository(
                WORKSPACE_ID,
                SECOND_PROJECT_ID,
                "octo",
                "demo",
                API_REFERENCE,
                WEBHOOK_REFERENCE
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(GitHubRepositoryErrorCode.REPOSITORY_BOUND_TO_ANOTHER_PROJECT));

        clearRepositoryData();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> concurrentBind(start, PROJECT_ID));
            Future<Boolean> second = executor.submit(() -> concurrentBind(start, SECOND_PROJECT_ID));
            start.countDown();

            assertThat(List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue))
                    .hasSize(1);
            assertThat(activeRepositoryCount(GITHUB_REPOSITORY_ID)).isEqualTo(1);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void disableStopsWebhookAndReactivateRestoresIt() throws Exception {
        authenticate(OWNER_ID, "owner");
        GitHubRepositoryResponse binding = bind(PROJECT_ID);
        GitHubRepositoryResponse disabled = bindingService.disableRepository(
                WORKSPACE_ID, PROJECT_ID, binding.id(), binding.version()
        );
        byte[] payload = pingPayload();

        mockMvc.perform(webhook(payload, "disabled-delivery"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("GITHUB_0406"));

        authenticate(OWNER_ID, "owner");
        GitHubRepositoryResponse reactivated = bindingService.reactivateRepository(
                WORKSPACE_ID, PROJECT_ID, binding.id(), disabled.version()
        );
        assertThat(reactivated.bindingStatus().name()).isEqualTo("ACTIVE");
        assertThat(reactivated.version()).isEqualTo(2);

        mockMvc.perform(webhook(payload, "reactivated-delivery"))
                .andExpect(status().isAccepted());
        await().untilAsserted(() -> {
            assertThat(deliveryStatus("reactivated-delivery")).isEqualTo("SUCCEEDED");
            assertThat(activityCount("reactivated-delivery")).isEqualTo(1);
        });
    }

    @Test
    void refreshSupportsRenameButRejectsRepositoryIdentityChange() {
        authenticate(OWNER_ID, "owner");
        GitHubRepositoryResponse binding = bind(PROJECT_ID);
        when(metadataClient.getRepository(
                eq("trusted-org"), eq("trusted-repo"), eq(API_REFERENCE),
                any(GitHubConditionalRequest.class)
        )).thenReturn(apiResponse(
                repository(GITHUB_REPOSITORY_ID, "renamed-org", "renamed-repo"),
                false,
                "\"etag-v2\""
        ));

        GitHubRepositoryResponse refreshed = bindingService.refreshRepositoryMetadata(
                WORKSPACE_ID, PROJECT_ID, binding.id(), binding.version()
        );

        assertThat(refreshed.githubRepositoryId()).isEqualTo(GITHUB_REPOSITORY_ID);
        assertThat(refreshed.fullName()).isEqualTo("renamed-org/renamed-repo");
        assertThat(refreshed.version()).isEqualTo(1);
        assertThat(repositoryEtag(binding.id())).isEqualTo("\"etag-v2\"");

        when(metadataClient.getRepository(
                eq("renamed-org"), eq("renamed-repo"), eq(API_REFERENCE),
                any(GitHubConditionalRequest.class)
        )).thenReturn(apiResponse(null, true, "\"etag-v2\""));
        GitHubRepositoryResponse notModified = bindingService.refreshRepositoryMetadata(
                WORKSPACE_ID, PROJECT_ID, binding.id(), refreshed.version()
        );
        assertThat(notModified.fullName()).isEqualTo("renamed-org/renamed-repo");
        assertThat(notModified.version()).isEqualTo(2);
        assertThat(repositoryEtag(binding.id())).isEqualTo("\"etag-v2\"");

        when(metadataClient.getRepository(
                eq("renamed-org"), eq("renamed-repo"), eq(API_REFERENCE),
                any(GitHubConditionalRequest.class)
        )).thenReturn(apiResponse(
                repository(999_999L, "different", "identity"), false, "\"etag-v3\""
        ));
        assertThatThrownBy(() -> bindingService.refreshRepositoryMetadata(
                WORKSPACE_ID, PROJECT_ID, binding.id(), notModified.version()
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(GitHubRepositoryErrorCode.GITHUB_REPOSITORY_ID_MISMATCH));
        assertThat(repositoryFullName(binding.id())).isEqualTo("renamed-org/renamed-repo");
    }

    @Test
    void unbindRetainsEventsAllowsTwoRebindRoundsAndMakesWebhookNotFound() throws Exception {
        authenticate(OWNER_ID, "owner");
        GitHubRepositoryResponse first = bind(PROJECT_ID);
        byte[] payload = pingPayload();
        mockMvc.perform(webhook(payload, "historical-delivery"))
                .andExpect(status().isAccepted());
        await().untilAsserted(() ->
                assertThat(deliveryStatus("historical-delivery")).isEqualTo("SUCCEEDED"));

        authenticate(OWNER_ID, "owner");
        bindingService.unbindRepository(
                WORKSPACE_ID, PROJECT_ID, first.id(), first.version()
        );
        mockMvc.perform(webhook(payload, "after-unbind-delivery"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("GITHUB_0405"));

        authenticate(OWNER_ID, "owner");
        GitHubRepositoryResponse second = bind(PROJECT_ID);
        bindingService.unbindRepository(
                WORKSPACE_ID, PROJECT_ID, second.id(), second.version()
        );
        GitHubRepositoryResponse third = bind(PROJECT_ID);

        assertThat(List.of(first.id(), second.id(), third.id())).doesNotHaveDuplicates();
        assertThat(deletedRepositoryCount(GITHUB_REPOSITORY_ID)).isEqualTo(2);
        assertThat(activeRepositoryCount(GITHUB_REPOSITORY_ID)).isEqualTo(1);
        assertThat(deliveryCount("historical-delivery")).isEqualTo(1);
        assertThat(activityCount("historical-delivery")).isEqualTo(1);
    }

    @Test
    void versionScopePrivateProjectAndAuthenticationBoundariesAreEnforced() throws Exception {
        authenticate(OWNER_ID, "owner");
        GitHubRepositoryResponse binding = bind(PROJECT_ID);
        bindingService.disableRepository(WORKSPACE_ID, PROJECT_ID, binding.id(), 0);

        assertThatThrownBy(() -> bindingService.disableRepository(
                WORKSPACE_ID, PROJECT_ID, binding.id(), 0
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode())
                        .isEqualTo(GitHubRepositoryErrorCode.REPOSITORY_BINDING_VERSION_CONFLICT));

        mockMvc.perform(get(itemPath(OTHER_WORKSPACE_ID, OTHER_PROJECT_ID, binding.id()))
                        .with(authentication(testAuthentication(OWNER_ID, "owner"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));
        mockMvc.perform(get(itemPath(WORKSPACE_ID, PROJECT_ID, binding.id()))
                        .with(authentication(testAuthentication(MEMBER_ID, "member"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));
        mockMvc.perform(get(itemPath(WORKSPACE_ID, PROJECT_ID, binding.id())))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0401"));
    }

    private boolean concurrentBind(CountDownLatch start, long projectId) {
        try {
            start.await();
            authenticate(OWNER_ID, "owner");
            bind(projectId);
            return true;
        } catch (BusinessException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        } finally {
            SecurityContextHolder.clearContext();
        }
    }

    private GitHubRepositoryResponse bind(long projectId) {
        return bindingService.bindRepository(
                WORKSPACE_ID,
                projectId,
                "octo",
                "demo",
                API_REFERENCE,
                WEBHOOK_REFERENCE
        );
    }

    private void authenticate(long userId, String username) {
        SecurityContextHolder.getContext().setAuthentication(testAuthentication(userId, username));
    }

    private Authentication testAuthentication(long userId, String username) {
        DevPilotUserPrincipal principal = new DevPilotUserPrincipal(
                userId, username, username + "@example.com", username
        );
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private VerifiedGitHubRepository repository(long id, String owner, String name) {
        return new VerifiedGitHubRepository(
                id,
                owner,
                name,
                owner + "/" + name,
                "https://github.com/" + owner + "/" + name,
                "main",
                "private"
        );
    }

    private GitHubApiResponse<VerifiedGitHubRepository> apiResponse(
            VerifiedGitHubRepository repository,
            boolean notModified,
            String etag
    ) {
        return new GitHubApiResponse<>(
                notModified ? 304 : 200,
                repository,
                notModified,
                etag,
                Instant.parse("2026-07-31T10:00:00Z"),
                new GitHubRateLimitSnapshot(
                        5_000L, 4_999L, 1L, Instant.parse("2026-07-31T11:00:00Z"),
                        "core", null, "integration-request-id"
                ),
                GitHubPageCursor.empty()
        );
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM dp_project_activity");
        jdbcTemplate.update("DELETE FROM dp_github_delivery");
        jdbcTemplate.update("DELETE FROM dp_github_repository");
        jdbcTemplate.update("DELETE FROM dp_project_member");
        jdbcTemplate.update("DELETE FROM dp_workspace_member");
        jdbcTemplate.update("DELETE FROM dp_project");
        jdbcTemplate.update("DELETE FROM dp_workspace");
        jdbcTemplate.update("DELETE FROM dp_user");
    }

    private void clearRepositoryData() {
        jdbcTemplate.update("DELETE FROM dp_project_activity");
        jdbcTemplate.update("DELETE FROM dp_github_delivery");
        jdbcTemplate.update("DELETE FROM dp_github_repository");
    }

    private void insertUser(long id, String username) {
        jdbcTemplate.update("""
                INSERT INTO dp_user (
                    id, username, email, display_name, password_hash, status
                ) VALUES (?, ?, ?, ?, '{noop}not-used', 'ACTIVE')
                """, id, username, username + "@example.com", username);
    }

    private void insertWorkspace(long id, String slug, long ownerId) {
        jdbcTemplate.update("""
                INSERT INTO dp_workspace (id, name, slug, owner_user_id, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, id, slug, slug, ownerId);
    }

    private void insertWorkspaceMember(long workspaceId, long userId, String role) {
        jdbcTemplate.update("""
                INSERT INTO dp_workspace_member (
                    workspace_id, user_id, role, status, invited_by, joined_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP(6))
                """, workspaceId, userId, role, OWNER_ID);
    }

    private void insertProject(long id, long workspaceId, String key, long createdBy) {
        jdbcTemplate.update("""
                INSERT INTO dp_project (
                    id, workspace_id, name, project_key, status, visibility, created_by
                ) VALUES (?, ?, ?, ?, 'ACTIVE', 'PRIVATE', ?)
                """, id, workspaceId, key, key, createdBy);
    }

    private org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder webhook(
            byte[] payload,
            String deliveryId
    ) {
        return post("/api/v1/github/webhooks")
                .contentType(MediaType.APPLICATION_JSON)
                .header("X-Hub-Signature-256", signature(payload))
                .header("X-GitHub-Delivery", deliveryId)
                .header("X-GitHub-Event", "ping")
                .content(payload);
    }

    private byte[] pingPayload() {
        return ("""
                {"repository":{"id":%d,"full_name":"trusted-org/trusted-repo"},
                 "sender":{"id":7,"login":"octocat"}}
                """).formatted(GITHUB_REPOSITORY_ID).getBytes(StandardCharsets.UTF_8);
    }

    private String signature(byte[] payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(WEBHOOK_SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return "sha256=" + HexFormat.of().formatHex(mac.doFinal(payload));
        } catch (Exception exception) {
            throw new IllegalStateException("Cannot sign test payload", exception);
        }
    }

    private String collectionPath(long workspaceId, long projectId) {
        return "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId
                + "/github-repositories";
    }

    private String itemPath(long workspaceId, long projectId, long bindingId) {
        return collectionPath(workspaceId, projectId) + "/" + bindingId;
    }

    private String repositoryOwner(long githubRepositoryId) {
        return jdbcTemplate.queryForObject(
                "SELECT owner_login FROM dp_github_repository WHERE github_repository_id = ? AND deleted = 0",
                String.class,
                githubRepositoryId
        );
    }

    private long repositoryCreatedBy(long githubRepositoryId) {
        return jdbcTemplate.queryForObject(
                "SELECT created_by FROM dp_github_repository WHERE github_repository_id = ? AND deleted = 0",
                Long.class,
                githubRepositoryId
        );
    }

    private String repositoryFullName(long bindingId) {
        return jdbcTemplate.queryForObject(
                "SELECT full_name FROM dp_github_repository WHERE id = ?",
                String.class,
                bindingId
        );
    }

    private String repositoryEtag(long bindingId) {
        return jdbcTemplate.queryForObject(
                "SELECT metadata_etag FROM dp_github_repository WHERE id = ?",
                String.class,
                bindingId
        );
    }

    private int activeRepositoryCount(long githubRepositoryId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_github_repository WHERE github_repository_id = ? AND deleted = 0",
                Integer.class,
                githubRepositoryId
        );
        return count == null ? 0 : count;
    }

    private int deletedRepositoryCount(long githubRepositoryId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_github_repository WHERE github_repository_id = ? AND deleted = 1",
                Integer.class,
                githubRepositoryId
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
}
