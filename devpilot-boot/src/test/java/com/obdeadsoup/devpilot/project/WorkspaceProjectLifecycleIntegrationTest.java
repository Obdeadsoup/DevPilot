package com.obdeadsoup.devpilot.project;

import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.project.application.ProjectActivityService;
import com.obdeadsoup.devpilot.project.application.ProjectService;
import com.obdeadsoup.devpilot.project.application.WorkspaceService;
import com.obdeadsoup.devpilot.project.application.command.RecordProjectActivityCommand;
import com.obdeadsoup.devpilot.project.domain.ProjectActivitySourceType;
import com.obdeadsoup.devpilot.project.domain.ProjectActivityType;
import com.obdeadsoup.devpilot.project.domain.ProjectStatus;
import com.obdeadsoup.devpilot.project.domain.ProjectVisibility;
import com.obdeadsoup.devpilot.project.error.ProjectErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class WorkspaceProjectLifecycleIntegrationTest {

    private static final long OWNER_ID = 1L;
    private static final long OTHER_OWNER_ID = 2L;
    private static final long MEMBER_ID = 3L;
    private static final long OUTSIDER_ID = 4L;
    private static final long WORKSPACE_ID = 100L;
    private static final long OTHER_WORKSPACE_ID = 101L;
    private static final long PRIVATE_PROJECT_ID = 200L;
    private static final long INTERNAL_PROJECT_ID = 201L;
    private static final long OTHER_PROJECT_ID = 300L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_lifecycle_test")
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
    private WorkspaceService workspaceService;

    @Autowired
    private ProjectService projectService;

    @Autowired
    private ProjectActivityService projectActivityService;

    @BeforeEach
    void setUp() {
        SecurityContextHolder.clearContext();
        clearData();
        insertUser(OWNER_ID, "owner");
        insertUser(OTHER_OWNER_ID, "other-owner");
        insertUser(MEMBER_ID, "member");
        insertUser(OUTSIDER_ID, "outsider");
        insertWorkspace(WORKSPACE_ID, "main-workspace", OWNER_ID, "ACTIVE");
        insertWorkspace(OTHER_WORKSPACE_ID, "other-workspace", OTHER_OWNER_ID, "ACTIVE");
        insertWorkspaceMember(WORKSPACE_ID, MEMBER_ID, "MEMBER");
        insertProject(PRIVATE_PROJECT_ID, WORKSPACE_ID, "PRIVATE", "PRIVATE", "ACTIVE", OWNER_ID);
        insertProject(INTERNAL_PROJECT_ID, WORKSPACE_ID, "INTERNAL", "INTERNAL", "ACTIVE", OWNER_ID);
        insertProject(OTHER_PROJECT_ID, OTHER_WORKSPACE_ID, "OTHER", "PRIVATE", "ACTIVE", OTHER_OWNER_ID);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void flywayV5AddsCreatorVersionCheckAndActiveProjectKey() {
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history
                WHERE version = '5' AND success = 1
                """, Integer.class);
        Integer columns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'dp_project'
                  AND column_name IN ('created_by', 'active_project_key')
                """, Integer.class);
        Integer activeIndex = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'dp_project'
                  AND index_name = 'uk_project_workspace_active_key'
                """, Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(columns).isEqualTo(2);
        assertThat(activeIndex).isEqualTo(2);
    }

    @Test
    void workspaceSlugIsGloballyUniqueAndPermanentlyReserved() {
        jdbcTemplate.update("UPDATE dp_workspace SET deleted = 1 WHERE id = ?", WORKSPACE_ID);

        assertThatThrownBy(() -> insertWorkspace(999L, "main-workspace", OWNER_ID, "ACTIVE"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void activeProjectKeyIsUniqueInsideWorkspace() {
        assertThatThrownBy(() -> insertProject(
                999L, WORKSPACE_ID, "PRIVATE", "PRIVATE", "PLANNING", OWNER_ID
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void deletedProjectKeyCanBeCreatedAgain() {
        jdbcTemplate.update("UPDATE dp_project SET deleted = 1 WHERE id = ?", PRIVATE_PROJECT_ID);

        insertProject(999L, WORKSPACE_ID, "PRIVATE", "PRIVATE", "PLANNING", OWNER_ID);

        assertThat(activeProjectCount(WORKSPACE_ID, "PRIVATE")).isEqualTo(1);
    }

    @Test
    void sameProjectKeyCanAccumulateMoreThanOneDeletedHistoryRow() {
        jdbcTemplate.update("UPDATE dp_project SET deleted = 1 WHERE id = ?", PRIVATE_PROJECT_ID);
        insertProject(999L, WORKSPACE_ID, "PRIVATE", "PRIVATE", "PLANNING", OWNER_ID);

        jdbcTemplate.update("UPDATE dp_project SET deleted = 1 WHERE id = 999");

        Integer deletedCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dp_project
                WHERE workspace_id = ? AND project_key = 'PRIVATE' AND deleted = 1
                """, Integer.class, WORKSPACE_ID);
        assertThat(deletedCount).isEqualTo(2);
    }

    @Test
    void projectMustReferenceAnExistingWorkspace() {
        assertThatThrownBy(() -> insertProject(
                999L, 999_999L, "ORPHAN", "PRIVATE", "PLANNING", OWNER_ID
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void staleProjectProfileUpdateReturnsVersionConflict() {
        authenticate(OWNER_ID, "owner");
        projectService.updateProjectProfile(
                WORKSPACE_ID, PRIVATE_PROJECT_ID, "Renamed", null, ProjectVisibility.PRIVATE, 0
        );

        assertThatThrownBy(() -> projectService.updateProjectProfile(
                WORKSPACE_ID, PRIVATE_PROJECT_ID, "Stale", null, ProjectVisibility.PRIVATE, 0
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_VERSION_CONFLICT));
        assertThat(projectName(PRIVATE_PROJECT_ID)).isEqualTo("Renamed");
    }

    @Test
    void concurrentArchiveWithSameVersionHasAtMostOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(() -> archiveAsOwner(start));
            Future<Boolean> second = executor.submit(() -> archiveAsOwner(start));
            start.countDown();

            assertThat(List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue))
                    .hasSize(1);
            assertThat(projectStatus(PRIVATE_PROJECT_ID)).isEqualTo("ARCHIVED");
            assertThat(projectVersion(PRIVATE_PROJECT_ID)).isEqualTo(1L);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void invalidProjectStatusTransitionFailsWithoutMutation() {
        authenticate(OWNER_ID, "owner");

        assertThatThrownBy(() -> projectService.activateProject(
                WORKSPACE_ID, PRIVATE_PROJECT_ID, 0
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ProjectErrorCode.INVALID_PROJECT_STATUS_TRANSITION));
        assertThat(projectStatus(PRIVATE_PROJECT_ID)).isEqualTo("ACTIVE");
        assertThat(projectVersion(PRIVATE_PROJECT_ID)).isZero();
    }

    @Test
    void privateProjectIsAbsentFromMemberListAndCannotBeRead() throws Exception {
        mockMvc.perform(get(projectCollectionPath())
                        .with(authentication(testAuthentication(MEMBER_ID, "member"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].projectKey").value("INTERNAL"));

        mockMvc.perform(get(projectPath(PRIVATE_PROJECT_ID))
                        .with(authentication(testAuthentication(MEMBER_ID, "member"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));
    }

    @Test
    void projectListUsesWhitelistedStatusAndVisibilityFilters() throws Exception {
        mockMvc.perform(get(projectCollectionPath())
                        .param("visibility", "PRIVATE")
                        .with(authentication(testAuthentication(OWNER_ID, "owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].projectKey").value("PRIVATE"));

        mockMvc.perform(get(projectCollectionPath())
                        .param("status", "PLANNING")
                        .with(authentication(testAuthentication(OWNER_ID, "owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));

        mockMvc.perform(get(projectCollectionPath())
                        .param("visibility", "PUBLIC")
                        .with(authentication(testAuthentication(OWNER_ID, "owner"))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_0400"));
    }

    @Test
    void workspaceListIsSqlScopedToOwnershipOrActiveMembership() throws Exception {
        mockMvc.perform(get("/api/v1/workspaces")
                        .with(authentication(testAuthentication(MEMBER_ID, "member"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].id").value(WORKSPACE_ID));

        jdbcTemplate.update("""
                UPDATE dp_workspace_member SET status = 'REMOVED'
                WHERE workspace_id = ? AND user_id = ?
                """, WORKSPACE_ID, MEMBER_ID);
        mockMvc.perform(get("/api/v1/workspaces")
                        .with(authentication(testAuthentication(MEMBER_ID, "member"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(0));
    }

    @Test
    void internalProjectIsVisibleAndReadableToActiveWorkspaceMember() throws Exception {
        mockMvc.perform(get(projectPath(INTERNAL_PROJECT_ID))
                        .with(authentication(testAuthentication(MEMBER_ID, "member"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(INTERNAL_PROJECT_ID))
                .andExpect(jsonPath("$.data.visibility").value("INTERNAL"));
    }

    @Test
    void crossWorkspaceProjectIdCannotBeReadThroughWrongScope() throws Exception {
        mockMvc.perform(get(projectPath(OTHER_PROJECT_ID))
                        .with(authentication(testAuthentication(OWNER_ID, "owner"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));
    }

    @Test
    void disabledWorkspaceRejectsOrdinaryProjectAccess() throws Exception {
        jdbcTemplate.update("UPDATE dp_workspace SET status = 'DISABLED' WHERE id = ?", WORKSPACE_ID);

        mockMvc.perform(get(projectPath(INTERNAL_PROJECT_ID))
                        .with(authentication(testAuthentication(MEMBER_ID, "member"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));
    }

    @Test
    void projectActivityTimelineRequiresProjectActivityRead() throws Exception {
        insertActivity(PRIVATE_PROJECT_ID, "activity-read-test");

        mockMvc.perform(get(activityPath(PRIVATE_PROJECT_ID))
                        .with(authentication(testAuthentication(MEMBER_ID, "member"))))
                .andExpect(status().isForbidden());

        insertProjectMember(PRIVATE_PROJECT_ID, MEMBER_ID, "VIEWER");
        mockMvc.perform(get(activityPath(PRIVATE_PROJECT_ID))
                        .with(authentication(testAuthentication(MEMBER_ID, "member"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void archivedProjectKeepsExistingActivityReadableButRejectsNewInternalActivity() throws Exception {
        insertActivity(PRIVATE_PROJECT_ID, "archived-existing");
        jdbcTemplate.update(
                "UPDATE dp_project SET status = 'ARCHIVED' WHERE id = ?", PRIVATE_PROJECT_ID
        );

        mockMvc.perform(get(activityPath(PRIVATE_PROJECT_ID))
                        .with(authentication(testAuthentication(OWNER_ID, "owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        assertThatThrownBy(() -> projectActivityService.recordGitHubActivity(
                githubActivity("archived-new")
        )).isInstanceOfSatisfying(BusinessException.class, exception ->
                assertThat(exception.errorCode()).isEqualTo(ProjectErrorCode.PROJECT_NOT_FOUND));
        assertThat(activityCount("archived-new")).isZero();
    }

    @Test
    void unauthenticatedLifecycleEndpointReturnsJson401() throws Exception {
        mockMvc.perform(get(projectCollectionPath()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0401"));
    }

    @Test
    void authenticatedUserWithoutScopeReturnsJson403() throws Exception {
        mockMvc.perform(get(projectPath(PRIVATE_PROJECT_ID))
                        .with(authentication(testAuthentication(OUTSIDER_ID, "outsider"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));
    }

    @Test
    void webhookEndpointRemainsPublic() throws Exception {
        mockMvc.perform(post("/api/v1/github/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GITHUB_0400"));
    }

    @Test
    void recordGitHubActivityUsesTrustedInternalScopeWithoutLogin() {
        SecurityContextHolder.clearContext();
        boolean inserted = projectActivityService.recordGitHubActivity(githubActivity(
                "trusted-internal-delivery"
        ));

        assertThat(inserted).isTrue();
        assertThat(activityCount("trusted-internal-delivery")).isEqualTo(1);
    }

    private RecordProjectActivityCommand githubActivity(String deliveryId) {
        return new RecordProjectActivityCommand(
                WORKSPACE_ID,
                PRIVATE_PROJECT_ID,
                null,
                "octo/devpilot",
                ProjectActivitySourceType.GITHUB,
                ProjectActivityType.GITHUB_WEBHOOK_PING,
                deliveryId,
                7L,
                "octocat",
                null,
                null,
                null,
                null,
                null,
                "GitHub ping",
                null,
                null,
                LocalDateTime.of(2026, 7, 29, 12, 0)
        );
    }

    @Test
    void ordinaryWorkspaceMemberCreatingProjectBecomesProjectAdmin() {
        authenticate(MEMBER_ID, "member");

        var response = projectService.createProject(
                WORKSPACE_ID, "Member Project", "mem1", null, null
        );

        assertThat(response.status()).isEqualTo(ProjectStatus.PLANNING);
        assertThat(response.visibility()).isEqualTo(ProjectVisibility.PRIVATE);
        assertThat(response.projectKey()).isEqualTo("MEM1");
        assertThat(projectCreatedBy(response.id())).isEqualTo(MEMBER_ID);
        assertThat(projectMemberRole(response.id(), MEMBER_ID)).isEqualTo("PROJECT_ADMIN");
    }

    @Test
    void workspaceOwnerCanCreateDisableAndReactivateWorkspace() {
        authenticate(OWNER_ID, "owner");
        var created = workspaceService.createWorkspace(
                "Learning", " Learning-Space ", " lifecycle "
        );

        var disabled = workspaceService.disableWorkspace(created.id(), created.version());
        var reactivated = workspaceService.reactivateWorkspace(
                disabled.id(), disabled.version()
        );

        assertThat(created.slug()).isEqualTo("learning-space");
        assertThat(created.ownerUserId()).isEqualTo(OWNER_ID);
        assertThat(disabled.status().name()).isEqualTo("DISABLED");
        assertThat(reactivated.status().name()).isEqualTo("ACTIVE");
        assertThat(reactivated.version()).isEqualTo(2);
    }

    private boolean archiveAsOwner(CountDownLatch start) {
        try {
            start.await();
            authenticate(OWNER_ID, "owner");
            projectService.archiveProject(WORKSPACE_ID, PRIVATE_PROJECT_ID, 0);
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

    private void authenticate(long userId, String username) {
        SecurityContextHolder.getContext().setAuthentication(testAuthentication(userId, username));
    }

    private UsernamePasswordAuthenticationToken testAuthentication(long userId, String username) {
        DevPilotUserPrincipal principal = new DevPilotUserPrincipal(
                userId, username, username + "@example.com", username
        );
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
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

    private void insertUser(long id, String username) {
        jdbcTemplate.update("""
                INSERT INTO dp_user (
                    id, username, email, display_name, password_hash, status
                ) VALUES (?, ?, ?, ?, '{noop}not-used', 'ACTIVE')
                """, id, username, username + "@example.com", username);
    }

    private void insertWorkspace(long id, String slug, long ownerId, String status) {
        jdbcTemplate.update("""
                INSERT INTO dp_workspace (id, name, slug, owner_user_id, status)
                VALUES (?, ?, ?, ?, ?)
                """, id, slug, slug, ownerId, status);
    }

    private void insertWorkspaceMember(long workspaceId, long userId, String role) {
        jdbcTemplate.update("""
                INSERT INTO dp_workspace_member (
                    workspace_id, user_id, role, status, invited_by, joined_at
                ) VALUES (?, ?, ?, 'ACTIVE', ?, CURRENT_TIMESTAMP(6))
                """, workspaceId, userId, role, OWNER_ID);
    }

    private void insertProject(
            long id,
            long workspaceId,
            String projectKey,
            String visibility,
            String status,
            long createdBy
    ) {
        jdbcTemplate.update("""
                INSERT INTO dp_project (
                    id, workspace_id, name, project_key, status, visibility, created_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """, id, workspaceId, projectKey, projectKey, status, visibility, createdBy);
    }

    private void insertProjectMember(long projectId, long userId, String role) {
        jdbcTemplate.update("""
                INSERT INTO dp_project_member (
                    workspace_id, project_id, user_id, role, status, created_by
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """, WORKSPACE_ID, projectId, userId, role, OWNER_ID);
    }

    private void insertActivity(long projectId, String deliveryId) {
        jdbcTemplate.update("""
                INSERT INTO dp_project_activity (
                    workspace_id, project_id, source_type, activity_type,
                    source_delivery_id, title, occurred_at
                ) VALUES (?, ?, 'GITHUB', 'GITHUB_WEBHOOK_PING', ?, 'Activity', CURRENT_TIMESTAMP(6))
                """, WORKSPACE_ID, projectId, deliveryId);
    }

    private int activeProjectCount(long workspaceId, String projectKey) {
        Integer count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM dp_project
                WHERE workspace_id = ? AND project_key = ? AND deleted = 0
                """, Integer.class, workspaceId, projectKey);
        return count == null ? 0 : count;
    }

    private String projectName(long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT name FROM dp_project WHERE id = ?", String.class, projectId
        );
    }

    private String projectStatus(long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM dp_project WHERE id = ?", String.class, projectId
        );
    }

    private long projectVersion(long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM dp_project WHERE id = ?", Long.class, projectId
        );
    }

    private long projectCreatedBy(long projectId) {
        return jdbcTemplate.queryForObject(
                "SELECT created_by FROM dp_project WHERE id = ?", Long.class, projectId
        );
    }

    private String projectMemberRole(long projectId, long userId) {
        return jdbcTemplate.queryForObject("""
                SELECT role FROM dp_project_member
                WHERE project_id = ? AND user_id = ?
                """, String.class, projectId, userId);
    }

    private int activityCount(String deliveryId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_project_activity WHERE source_delivery_id = ?",
                Integer.class,
                deliveryId
        );
        return count == null ? 0 : count;
    }

    private String projectCollectionPath() {
        return "/api/v1/workspaces/" + WORKSPACE_ID + "/projects";
    }

    private String projectPath(long projectId) {
        return projectCollectionPath() + "/" + projectId;
    }

    private String activityPath(long projectId) {
        return projectPath(projectId) + "/activities";
    }
}
