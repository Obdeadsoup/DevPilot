package com.obdeadsoup.devpilot.rbac;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
import com.obdeadsoup.devpilot.identity.application.WorkspaceMemberService;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import com.obdeadsoup.devpilot.identity.domain.WorkspaceRole;
import com.obdeadsoup.devpilot.identity.error.WorkspaceErrorCode;
import com.obdeadsoup.devpilot.project.application.ProjectAuthorizationService;
import com.obdeadsoup.devpilot.project.application.ProjectMemberService;
import com.obdeadsoup.devpilot.project.domain.ProjectPermission;
import com.obdeadsoup.devpilot.project.domain.ProjectRole;
import com.obdeadsoup.devpilot.project.error.ProjectErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("identity-integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class ScopedRbacIntegrationTest {

    private static final String PASSWORD = "rbac-test-password!";
    private static final long OWNER_ID = 1L;
    private static final long ADMIN_ID = 2L;
    private static final long MEMBER_ID = 3L;
    private static final long VIEWER_ID = 4L;
    private static final long OUTSIDER_ID = 5L;
    private static final long OTHER_OWNER_ID = 6L;
    private static final long WORKSPACE_ID = 100L;
    private static final long OTHER_WORKSPACE_ID = 101L;
    private static final long PRIVATE_PROJECT_ID = 200L;
    private static final long INTERNAL_PROJECT_ID = 201L;
    private static final long OTHER_PROJECT_ID = 300L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_rbac_test")
            .withUsername("devpilot")
            .withPassword("devpilot_test_password");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>("redis:7.4-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void infrastructureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
    }

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private WorkspaceMemberService workspaceMemberService;

    @Autowired
    private ProjectMemberService projectMemberService;

    @Autowired
    private ProjectAuthorizationService projectAuthorizationService;

    @BeforeEach
    void setUp() {
        clearSecurityContext();
        clearData();
        String passwordHash = passwordEncoder.encode(PASSWORD);
        insertUser(OWNER_ID, "owner", passwordHash);
        insertUser(ADMIN_ID, "admin", passwordHash);
        insertUser(MEMBER_ID, "member", passwordHash);
        insertUser(VIEWER_ID, "viewer", passwordHash);
        insertUser(OUTSIDER_ID, "outsider", passwordHash);
        insertUser(OTHER_OWNER_ID, "other-owner", passwordHash);
        insertWorkspace(WORKSPACE_ID, "rbac-workspace", OWNER_ID);
        insertWorkspace(OTHER_WORKSPACE_ID, "other-workspace", OTHER_OWNER_ID);
        insertProject(PRIVATE_PROJECT_ID, WORKSPACE_ID, "PRIVATE", "PRIVATE");
        insertProject(INTERNAL_PROJECT_ID, WORKSPACE_ID, "INTERNAL", "INTERNAL");
        insertProject(OTHER_PROJECT_ID, OTHER_WORKSPACE_ID, "OTHER", "PRIVATE");
        insertWorkspaceMember(ADMIN_ID, "ADMIN", "ACTIVE");
        insertWorkspaceMember(MEMBER_ID, "MEMBER", "ACTIVE");
        insertWorkspaceMember(VIEWER_ID, "VIEWER", "ACTIVE");
        insertActivity(PRIVATE_PROJECT_ID, "rbac-private-activity");
        insertActivity(INTERNAL_PROJECT_ID, "rbac-internal-activity");
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void flywayV4CreatesOwnershipAndScopedMembershipConstraints() {
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '4' AND success = 1
                """, Integer.class);
        assertThat(applied).isEqualTo(1);

        assertThatThrownBy(() -> insertWorkspaceMember(MEMBER_ID, "VIEWER", "ACTIVE"))
                .isInstanceOf(RuntimeException.class);
        insertProjectMember(PRIVATE_PROJECT_ID, MEMBER_ID, "DEVELOPER");
        assertThatThrownBy(() -> insertProjectMember(PRIVATE_PROJECT_ID, MEMBER_ID, "VIEWER"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> jdbcTemplate.update("""
                INSERT INTO dp_project_member (
                    workspace_id, project_id, user_id, role, status, created_by
                ) VALUES (?, ?, ?, 'VIEWER', 'ACTIVE', ?)
                """, OTHER_WORKSPACE_ID, PRIVATE_PROJECT_ID, VIEWER_ID, OWNER_ID))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertWorkspace(999L, "invalid-owner", 999_999L))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void nonWorkspaceMemberCannotBecomeProjectMember() {
        authenticate(OWNER_ID, "owner");

        assertThatThrownBy(() -> projectMemberService.addMember(
                WORKSPACE_ID, PRIVATE_PROJECT_ID, OUTSIDER_ID, ProjectRole.VIEWER
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ProjectErrorCode.USER_NOT_WORKSPACE_MEMBER));
        assertThat(projectMemberCount(PRIVATE_PROJECT_ID, OUTSIDER_ID)).isZero();
    }

    @Test
    void workspaceAndProjectRoleUpdatesRejectStaleVersions() {
        authenticate(OWNER_ID, "owner");

        workspaceMemberService.changeMemberRole(
                WORKSPACE_ID, MEMBER_ID, WorkspaceRole.VIEWER, 0L
        );
        assertThatThrownBy(() -> workspaceMemberService.changeMemberRole(
                WORKSPACE_ID, MEMBER_ID, WorkspaceRole.MEMBER, 0L
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(WorkspaceErrorCode.MEMBERSHIP_VERSION_CONFLICT));

        insertProjectMember(PRIVATE_PROJECT_ID, VIEWER_ID, "VIEWER");
        projectMemberService.changeRole(
                WORKSPACE_ID, PRIVATE_PROJECT_ID, VIEWER_ID, ProjectRole.DEVELOPER, 0L
        );
        assertThatThrownBy(() -> projectMemberService.changeRole(
                WORKSPACE_ID, PRIVATE_PROJECT_ID, VIEWER_ID, ProjectRole.VIEWER, 0L
        ))
                .isInstanceOfSatisfying(BusinessException.class, exception ->
                        assertThat(exception.errorCode())
                                .isEqualTo(ProjectErrorCode.PROJECT_MEMBERSHIP_VERSION_CONFLICT));
    }

    @Test
    void concurrentOwnershipTransfersAllowAtMostOneWinner() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<Boolean> first = executor.submit(
                    () -> transferAsOwner(start, MEMBER_ID)
            );
            Future<Boolean> second = executor.submit(
                    () -> transferAsOwner(start, VIEWER_ID)
            );
            start.countDown();

            assertThat(List.of(first.get(), second.get()).stream().filter(Boolean::booleanValue))
                    .hasSize(1);
            Long ownerUserId = jdbcTemplate.queryForObject(
                    "SELECT owner_user_id FROM dp_workspace WHERE id = ?",
                    Long.class,
                    WORKSPACE_ID
            );
            assertThat(ownerUserId).isIn(MEMBER_ID, VIEWER_ID);
            assertThat(workspaceMemberRole(OWNER_ID)).isEqualTo("ADMIN");
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void removingWorkspaceMemberRevokesProjectMembershipAndPermissionInSameTransaction() {
        insertProjectMember(PRIVATE_PROJECT_ID, MEMBER_ID, "DEVELOPER");
        assertThat(projectAuthorizationService.hasPermission(
                MEMBER_ID,
                WORKSPACE_ID,
                PRIVATE_PROJECT_ID,
                ProjectPermission.PROJECT_ACTIVITY_READ
        )).isTrue();
        authenticate(OWNER_ID, "owner");

        workspaceMemberService.removeMember(WORKSPACE_ID, MEMBER_ID, 0L);

        assertThat(workspaceMemberStatus(MEMBER_ID)).isEqualTo("REMOVED");
        assertThat(projectMemberStatus(PRIVATE_PROJECT_ID, MEMBER_ID)).isEqualTo("REMOVED");
        assertThat(projectAuthorizationService.hasPermission(
                MEMBER_ID,
                WORKSPACE_ID,
                PRIVATE_PROJECT_ID,
                ProjectPermission.PROJECT_ACTIVITY_READ
        )).isFalse();
    }

    @Test
    void realBearerAuthorizationEnforcesScopeVisibilityAndJsonErrors() throws Exception {
        String ownerToken = login("owner");
        String memberToken = login("member");
        String outsiderToken = login("outsider");

        mockMvc.perform(get(activityPath(WORKSPACE_ID, OTHER_PROJECT_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer(ownerToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));

        mockMvc.perform(get(activityPath(WORKSPACE_ID, PRIVATE_PROJECT_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));

        mockMvc.perform(get(activityPath(WORKSPACE_ID, INTERNAL_PROJECT_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer(memberToken)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));

        mockMvc.perform(get(activityPath(WORKSPACE_ID, PRIVATE_PROJECT_ID)))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0401"));

        mockMvc.perform(get(activityPath(WORKSPACE_ID, INTERNAL_PROJECT_ID))
                        .header(HttpHeaders.AUTHORIZATION, bearer(outsiderToken)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));
    }

    @Test
    void webhookEndpointRemainsPublicAndIndependentFromLocalRbac() throws Exception {
        mockMvc.perform(post("/api/v1/github/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GITHUB_0400"));
    }

    private boolean transferAsOwner(CountDownLatch start, long newOwnerId) {
        try {
            start.await();
            authenticate(OWNER_ID, "owner");
            workspaceMemberService.transferOwnership(WORKSPACE_ID, newOwnerId, 0L);
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
        DevPilotUserPrincipal principal = new DevPilotUserPrincipal(
                userId,
                username,
                username + "@example.com",
                username
        );
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(principal, null, List.of())
        );
    }

    private String login(String username) throws Exception {
        MvcResult result = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of(
                                "login", username,
                                "password", PASSWORD
                        ))))
                .andExpect(status().isOk())
                .andReturn();
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return response.path("data").path("accessToken").asText();
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
        try (RedisConnection connection = Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    private void insertUser(long id, String username, String passwordHash) {
        jdbcTemplate.update("""
                INSERT INTO dp_user (
                    id, username, email, display_name, password_hash, status
                ) VALUES (?, ?, ?, ?, ?, 'ACTIVE')
                """, id, username, username + "@example.com", username, passwordHash);
    }

    private void insertWorkspace(long id, String slug, long ownerUserId) {
        jdbcTemplate.update("""
                INSERT INTO dp_workspace (id, name, slug, owner_user_id, status)
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """, id, slug, slug, ownerUserId);
    }

    private void insertProject(
            long projectId,
            long workspaceId,
            String projectKey,
            String visibility
    ) {
        jdbcTemplate.update("""
                INSERT INTO dp_project (
                    id, workspace_id, name, project_key, status, visibility
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """, projectId, workspaceId, projectKey, projectKey, visibility);
    }

    private void insertWorkspaceMember(long userId, String role, String status) {
        jdbcTemplate.update("""
                INSERT INTO dp_workspace_member (
                    workspace_id, user_id, role, status, invited_by, joined_at
                ) VALUES (?, ?, ?, ?, ?, CURRENT_TIMESTAMP(6))
                """, WORKSPACE_ID, userId, role, status, OWNER_ID);
    }

    private void insertProjectMember(long projectId, long userId, String role) {
        jdbcTemplate.update("""
                INSERT INTO dp_project_member (
                    workspace_id, project_id, user_id, role, status, created_by
                ) VALUES (?, ?, ?, ?, 'ACTIVE', ?)
                """, WORKSPACE_ID, projectId, userId, role, OWNER_ID);
    }

    private void insertActivity(long projectId, String sourceDeliveryId) {
        jdbcTemplate.update("""
                INSERT INTO dp_project_activity (
                    workspace_id, project_id, source_type, activity_type,
                    source_delivery_id, title, occurred_at
                ) VALUES (?, ?, 'GITHUB', 'GITHUB_WEBHOOK_PING', ?, 'RBAC activity',
                          CURRENT_TIMESTAMP(6))
                """, WORKSPACE_ID, projectId, sourceDeliveryId);
    }

    private int projectMemberCount(long projectId, long userId) {
        Integer count = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM dp_project_member WHERE project_id = ? AND user_id = ?",
                Integer.class,
                projectId,
                userId
        );
        return count == null ? 0 : count;
    }

    private String workspaceMemberRole(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT role FROM dp_workspace_member WHERE workspace_id = ? AND user_id = ?",
                String.class,
                WORKSPACE_ID,
                userId
        );
    }

    private String workspaceMemberStatus(long userId) {
        return jdbcTemplate.queryForObject(
                "SELECT status FROM dp_workspace_member WHERE workspace_id = ? AND user_id = ?",
                String.class,
                WORKSPACE_ID,
                userId
        );
    }

    private String projectMemberStatus(long projectId, long userId) {
        return jdbcTemplate.queryForObject(
                """
                        SELECT status
                        FROM dp_project_member
                        WHERE workspace_id = ? AND project_id = ? AND user_id = ?
                        """,
                String.class,
                WORKSPACE_ID,
                projectId,
                userId
        );
    }

    private String activityPath(long workspaceId, long projectId) {
        return "/api/v1/workspaces/" + workspaceId + "/projects/" + projectId + "/activities";
    }

    private String bearer(String token) {
        return "Bearer " + token;
    }
}
