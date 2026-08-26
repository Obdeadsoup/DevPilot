package com.obdeadsoup.devpilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRunResult;
import com.obdeadsoup.devpilot.agent.application.AgentRunStatus;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimePort;
import com.obdeadsoup.devpilot.agent.infrastructure.grpc.AgentRuntimeClientException;
import com.obdeadsoup.devpilot.agent.infrastructure.grpc.AgentRuntimeFailureKind;
import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
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
class AgentRunHttpIntegrationTest {
    private static final long OWNER_ID = 1L;
    private static final long DEVELOPER_ID = 2L;
    private static final long VIEWER_ID = 3L;
    private static final long OUTSIDER_ID = 4L;
    private static final long WORKSPACE_ID = 100L;
    private static final long PROJECT_ID = 200L;
    private static final long OTHER_PROJECT_ID = 201L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_agent_run_test")
            .withUsername("devpilot")
            .withPassword("devpilot_test_password");

    @DynamicPropertySource
    static void databaseProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
    }

    @Autowired private JdbcTemplate jdbcTemplate;
    @Autowired private MockMvc mockMvc;
    @Autowired private ObjectMapper objectMapper;
    @MockitoBean private AgentRuntimePort runtimePort;

    @BeforeEach
    void setUp() {
        clearData();
        insertUser(OWNER_ID, "owner");
        insertUser(DEVELOPER_ID, "developer");
        insertUser(VIEWER_ID, "viewer");
        insertUser(OUTSIDER_ID, "outsider");
        jdbcTemplate.update("""
                INSERT INTO dp_workspace (id, name, slug, owner_user_id, status)
                VALUES (?, 'Agent Workspace', 'agent-workspace', ?, 'ACTIVE')
                """, WORKSPACE_ID, OWNER_ID);
        jdbcTemplate.update("""
                INSERT INTO dp_workspace_member (workspace_id, user_id, role, status, invited_by, joined_at)
                VALUES (?, ?, 'MEMBER', 'ACTIVE', ?, CURRENT_TIMESTAMP(6)),
                       (?, ?, 'VIEWER', 'ACTIVE', ?, CURRENT_TIMESTAMP(6))
                """, WORKSPACE_ID, DEVELOPER_ID, OWNER_ID, WORKSPACE_ID, VIEWER_ID, OWNER_ID);
        insertProject(PROJECT_ID, "AGENT");
        insertProject(OTHER_PROJECT_ID, "OTHER");
        jdbcTemplate.update("""
                INSERT INTO dp_project_member (workspace_id, project_id, user_id, role, status, created_by)
                VALUES (?, ?, ?, 'DEVELOPER', 'ACTIVE', ?),
                       (?, ?, ?, 'VIEWER', 'ACTIVE', ?)
                """, WORKSPACE_ID, PROJECT_ID, DEVELOPER_ID, OWNER_ID,
                WORKSPACE_ID, PROJECT_ID, VIEWER_ID, OWNER_ID);
    }

    @Test
    void flywayV14CreatesScopedStateProjectionAndIndexes() {
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version='14' AND success=1
                """, Integer.class);
        Integer indexes = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics
                WHERE table_schema=DATABASE() AND table_name='dp_agent_run'
                  AND index_name IN ('uk_agent_run_run_id', 'uk_agent_run_request_id',
                                     'idx_agent_run_scope_time', 'idx_agent_run_scope_status_time')
                """, Integer.class);

        assertThat(applied).isEqualTo(1);
        assertThat(indexes).isEqualTo(4);
    }

    @Test
    void developerCanStartAndViewerCanReadWhileRpcRunsOutsideTransaction() throws Exception {
        AtomicBoolean transactionActiveDuringRpc = new AtomicBoolean(true);
        when(runtimePort.run(any())).thenAnswer(invocation -> {
            transactionActiveDuringRpc.set(TransactionSynchronizationManager.isActualTransactionActive());
            AgentRunCommand command = invocation.getArgument(0);
            return new AgentRunResult(command.runId(), "review complete", AgentRunStatus.SUCCEEDED);
        });

        MvcResult start = mockMvc.perform(post(path())
                        .with(authentication(authToken(DEVELOPER_ID, "developer")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("input", "review this project"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON_0000"))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.finalOutput").value("review complete"))
                .andExpect(jsonPath("$.data.version").value(1))
                .andReturn();
        String runId = body(start).path("data").path("runId").asText();

        assertThat(transactionActiveDuringRpc).isFalse();
        mockMvc.perform(get(path() + "/" + runId)
                        .with(authentication(authToken(VIEWER_ID, "viewer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value(runId))
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    void viewerCannotStartAndOutsiderCannotRead() throws Exception {
        mockMvc.perform(post(path())
                        .with(authentication(authToken(VIEWER_ID, "viewer")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"hello\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));

        when(runtimePort.run(any())).thenAnswer(invocation -> {
            AgentRunCommand command = invocation.getArgument(0);
            return new AgentRunResult(command.runId(), "answer", AgentRunStatus.SUCCEEDED);
        });
        MvcResult start = startAs(DEVELOPER_ID, "developer");
        String runId = body(start).path("data").path("runId").asText();

        mockMvc.perform(get(path() + "/" + runId)
                        .with(authentication(authToken(OUTSIDER_ID, "outsider"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));
    }

    @Test
    void scopedLookupDoesNotExposeRunThroughAnotherProject() throws Exception {
        when(runtimePort.run(any())).thenAnswer(invocation -> {
            AgentRunCommand command = invocation.getArgument(0);
            return new AgentRunResult(command.runId(), "answer", AgentRunStatus.SUCCEEDED);
        });
        String runId = body(startAs(DEVELOPER_ID, "developer")).path("data").path("runId").asText();

        mockMvc.perform(get(path(OTHER_PROJECT_ID) + "/" + runId)
                        .with(authentication(authToken(OWNER_ID, "owner"))))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("AGENT_0401"));
    }

    @Test
    void sanitizedRpcFailureBecomesFailedRunAndRemainsQueryable() throws Exception {
        when(runtimePort.run(any())).thenThrow(new AgentRuntimeClientException(
                AgentRuntimeFailureKind.DEADLINE_EXCEEDED,
                new IllegalStateException("private provider response")));

        MvcResult start = startAs(DEVELOPER_ID, "developer");
        JsonNode response = body(start);
        String runId = response.path("data").path("runId").asText();

        assertThat(response.path("data").path("status").asText()).isEqualTo("FAILED");
        assertThat(response.path("data").path("failureKind").asText()).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(response.path("data").path("finalOutput").isNull()).isTrue();
        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT status, failure_kind, final_output FROM dp_agent_run WHERE run_id=?", runId);
        assertThat(stored.get("status")).isEqualTo("FAILED");
        assertThat(stored.get("failure_kind")).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(stored.get("final_output")).isNull();

        mockMvc.perform(get(path() + "/" + runId)
                        .with(authentication(authToken(VIEWER_ID, "viewer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.failureKind").value("DEADLINE_EXCEEDED"));
    }

    private MvcResult startAs(long userId, String username) throws Exception {
        return mockMvc.perform(post(path())
                        .with(authentication(authToken(userId, username)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"input\":\"hello agent\"}"))
                .andExpect(status().isOk())
                .andReturn();
    }

    private UsernamePasswordAuthenticationToken authToken(long userId, String username) {
        DevPilotUserPrincipal principal = new DevPilotUserPrincipal(
                userId, username, username + "@example.com", username);
        return new UsernamePasswordAuthenticationToken(principal, null, List.of());
    }

    private JsonNode body(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsByteArray());
    }

    private String path() {
        return path(PROJECT_ID);
    }

    private String path(long projectId) {
        return "/api/v1/workspaces/" + WORKSPACE_ID + "/projects/" + projectId + "/agent-runs";
    }

    private void clearData() {
        jdbcTemplate.update("DELETE FROM dp_agent_run");
        jdbcTemplate.update("DELETE FROM dp_project_member");
        jdbcTemplate.update("DELETE FROM dp_workspace_member");
        jdbcTemplate.update("DELETE FROM dp_project");
        jdbcTemplate.update("DELETE FROM dp_workspace");
        jdbcTemplate.update("DELETE FROM dp_user");
    }

    private void insertUser(long id, String username) {
        jdbcTemplate.update("""
                INSERT INTO dp_user (id, username, email, display_name, password_hash, status)
                VALUES (?, ?, ?, ?, '{noop}not-used', 'ACTIVE')
                """, id, username, username + "@example.com", username);
    }

    private void insertProject(long id, String projectKey) {
        jdbcTemplate.update("""
                INSERT INTO dp_project (id, workspace_id, name, project_key, status, visibility, created_by)
                VALUES (?, ?, ?, ?, 'ACTIVE', 'PRIVATE', ?)
                """, id, WORKSPACE_ID, projectKey, projectKey, OWNER_ID);
    }
}
