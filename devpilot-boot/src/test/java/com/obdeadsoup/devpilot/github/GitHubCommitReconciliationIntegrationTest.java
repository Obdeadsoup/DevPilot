package com.obdeadsoup.devpilot.github;

import com.obdeadsoup.devpilot.github.application.GitHubCommitReconciliationService;
import com.obdeadsoup.devpilot.github.application.GitHubSyncRunStateService;
import com.obdeadsoup.devpilot.github.application.client.GitHubApiResponse;
import com.obdeadsoup.devpilot.github.application.client.GitHubCommit;
import com.obdeadsoup.devpilot.github.application.client.GitHubCommitClient;
import com.obdeadsoup.devpilot.github.application.client.GitHubPage;
import com.obdeadsoup.devpilot.github.application.client.GitHubPageCursor;
import com.obdeadsoup.devpilot.github.application.client.GitHubRateLimitSnapshot;
import com.obdeadsoup.devpilot.github.application.client.GitHubRepositoryMetadataClient;
import com.obdeadsoup.devpilot.github.application.client.VerifiedGitHubRepository;
import com.obdeadsoup.devpilot.github.domain.GitHubSyncTriggerType;
import com.obdeadsoup.devpilot.github.persistence.entity.GitHubSyncRunEntity;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncCheckpointMapper;
import com.obdeadsoup.devpilot.github.persistence.mapper.GitHubSyncRunMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration-test")
@SpringBootTest
class GitHubCommitReconciliationIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_reconciliation_test")
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
    private GitHubSyncRunStateService stateService;

    @Autowired
    private GitHubCommitReconciliationService reconciliationService;

    @Autowired
    private GitHubSyncRunMapper runMapper;

    @Autowired
    private GitHubSyncCheckpointMapper checkpointMapper;

    @MockitoBean
    private GitHubRepositoryMetadataClient metadataClient;

    @MockitoBean
    private GitHubCommitClient commitClient;

    @BeforeEach
    void setUp() {
        WebhookTestFixture fixture = new WebhookTestFixture(jdbcTemplate);
        fixture.reset();
        fixture.createActiveBinding();
        when(metadataClient.getRepository(any(), any(), any(), any())).thenReturn(metadata());
    }

    @Test
    void apiReconciliationPersistsCommitThenAdvancesCheckpointAndRun() {
        GitHubCommit commit = commit("a", "2026-08-01T10:30:00Z");
        when(commitClient.listCommits(any(), any(), any(), any(Integer.class), any(), any()))
                .thenReturn(new GitHubPage<>(List.of(commit), GitHubPageCursor.empty()));
        GitHubSyncRunEntity pending = createRun();

        reconciliationService.reconcile(pending.id());

        assertThat(commitCount()).isEqualTo(1);
        assertThat(commitActivityCount(commit.sha())).isEqualTo(1);
        assertThat(runMapper.findById(pending.id()).orElseThrow().status()).isEqualTo("SUCCEEDED");
        assertThat(checkpointMapper.findCommitCheckpoint(WebhookTestFixture.REPOSITORY_ID)
                .orElseThrow().lastSuccessfulSyncAt())
                .isEqualTo(LocalDateTime.of(2026, 8, 1, 10, 30));
    }

    @Test
    void invalidSecondCommitDoesNotAdvanceCheckpointPastPartiallySavedPage() {
        GitHubCommit valid = commit("b", "2026-08-01T10:30:00Z");
        GitHubCommit invalid = new GitHubCommit(
                "not-a-sha", "invalid", null, null, null, null,
                Instant.parse("2026-08-01T10:31:00Z"),
                Instant.parse("2026-08-01T10:31:00Z"), null
        );
        when(commitClient.listCommits(any(), any(), any(), any(Integer.class), any(), any()))
                .thenReturn(new GitHubPage<>(List.of(valid, invalid), GitHubPageCursor.empty()));
        GitHubSyncRunEntity pending = createRun();

        reconciliationService.reconcile(pending.id());

        assertThat(commitCount()).isEqualTo(1);
        assertThat(runMapper.findById(pending.id()).orElseThrow().status()).isEqualTo("DEAD");
        assertThat(checkpointMapper.findCommitCheckpoint(WebhookTestFixture.REPOSITORY_ID)
                .orElseThrow())
                .satisfies(checkpoint -> {
                    assertThat(checkpoint.lastSuccessfulSyncAt()).isNull();
                    assertThat(checkpoint.lastSeenCommitSha()).isNull();
                });
    }

    private GitHubSyncRunEntity createRun() {
        return stateService.createOrGetOpen(
                WebhookTestFixture.REPOSITORY_ID,
                GitHubSyncTriggerType.INITIAL,
                null
        ).run();
    }

    private GitHubApiResponse<VerifiedGitHubRepository> metadata() {
        return new GitHubApiResponse<>(
                200,
                new VerifiedGitHubRepository(
                        WebhookTestFixture.GITHUB_REPOSITORY_ID,
                        "octo-org", "devpilot", "octo-org/devpilot",
                        "https://github.com/octo-org/devpilot", "main", "private"
                ),
                false, null, null,
                new GitHubRateLimitSnapshot(5000L, 4999L, 1L, null, "core", null, "request"),
                GitHubPageCursor.empty()
        );
    }

    private GitHubCommit commit(String character, String committedAt) {
        String sha = character.repeat(40);
        return new GitHubCommit(
                sha, "message", "Octo", "private@example.com", 7L, "octocat",
                Instant.parse(committedAt), Instant.parse(committedAt),
                "https://github.com/octo-org/devpilot/commit/" + sha
        );
    }

    private int commitCount() {
        Integer count = jdbcTemplate.queryForObject("SELECT COUNT(*) FROM dp_github_commit", Integer.class);
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
}
