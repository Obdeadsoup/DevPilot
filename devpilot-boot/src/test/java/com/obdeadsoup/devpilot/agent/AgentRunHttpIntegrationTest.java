package com.obdeadsoup.devpilot.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.obdeadsoup.devpilot.agent.application.AgentRunCommand;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeEventListener;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamFailureKind;
import com.obdeadsoup.devpilot.agent.application.AgentRuntimeStreamingPort;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEvent;
import com.obdeadsoup.devpilot.agent.application.AgentStreamEventType;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolApplicationService;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolCommand;
import com.obdeadsoup.devpilot.agent.application.tool.AgentToolResult;
import com.obdeadsoup.devpilot.framework.error.BusinessException;
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
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
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
    @Autowired private AgentToolApplicationService agentToolApplicationService;
    @MockitoBean private AgentRuntimeStreamingPort streamingPort;

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
    void flywayV14RemainsTheOnlyAgentRunDatabaseProjection() {
        Integer applied = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM flyway_schema_history WHERE version='14' AND success=1
                """, Integer.class);
        Integer eventTables = jdbcTemplate.queryForObject("""
                SELECT COUNT(*) FROM information_schema.tables
                WHERE table_schema=DATABASE() AND table_name='dp_agent_event'
                """, Integer.class);
        assertThat(applied).isEqualTo(1);
        assertThat(eventTables).isZero();
    }

    @Test
    void postReturnsAcceptedRunningBeforeAsyncTerminalAndRpcIsOutsideTransaction() throws Exception {
        AtomicBoolean transactionActive = new AtomicBoolean(true);
        AtomicReference<AgentRuntimeEventListener> listener = captureStream(transactionActive);

        MvcResult start = startAs(DEVELOPER_ID, "developer");
        JsonNode response = body(start);
        String runId = response.path("data").path("runId").asText();

        assertThat(response.path("data").path("status").asText()).isEqualTo("RUNNING");
        assertThat(response.path("data").path("version").asInt()).isZero();
        assertThat(transactionActive).isFalse();
        assertThat(listener.get()).isNotNull();

        listener.get().onEvent(event(runId, 1, AgentStreamEventType.RUN_STARTED, 0, "", "", ""));
        listener.get().onEvent(event(runId, 2, AgentStreamEventType.RUN_SUCCEEDED,
                0, "", "review complete", ""));
        listener.get().onCompleted();

        mockMvc.perform(get(path() + "/" + runId)
                        .with(authentication(authToken(VIEWER_ID, "viewer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"))
                .andExpect(jsonPath("$.data.finalOutput").value("review complete"))
                .andExpect(jsonPath("$.data.version").value(1));
    }

    @Test
    void sseCarriesIdNameAndDtoThenTerminalMatchesAuthoritativeGet() throws Exception {
        AtomicReference<AgentRuntimeEventListener> listener = captureStream(new AtomicBoolean());
        String runId = body(startAs(DEVELOPER_ID, "developer")).path("data").path("runId").asText();

        MvcResult stream = mockMvc.perform(get(path() + "/" + runId + "/stream")
                        .with(authentication(authToken(VIEWER_ID, "viewer")))
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();

        listener.get().onEvent(event(runId, 1, AgentStreamEventType.RUN_STARTED, 0, "", "", ""));
        listener.get().onEvent(event(runId, 2, AgentStreamEventType.MODEL_STEP_STARTED,
                1, "", "", ""));
        listener.get().onEvent(event(runId, 3, AgentStreamEventType.RUN_SUCCEEDED,
                0, "", "answer", ""));
        listener.get().onCompleted();

        MvcResult completed = mockMvc.perform(asyncDispatch(stream))
                .andExpect(status().isOk())
                .andReturn();
        String body = completed.getResponse().getContentAsString();
        assertThat(body).contains("id:" + runId + ":1", "event:run-started",
                "event:model-step-started", "event:run-succeeded", "\"runId\":\"" + runId + "\"");

        mockMvc.perform(get(path() + "/" + runId)
                        .with(authentication(authToken(VIEWER_ID, "viewer"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("SUCCEEDED"));
    }

    @Test
    void lastEventIdReplaysOnlyNewerEventsAndTerminalCanBeReplayed() throws Exception {
        AtomicReference<AgentRuntimeEventListener> listener = captureStream(new AtomicBoolean());
        String runId = body(startAs(DEVELOPER_ID, "developer")).path("data").path("runId").asText();
        listener.get().onEvent(event(runId, 1, AgentStreamEventType.RUN_STARTED, 0, "", "", ""));
        listener.get().onEvent(event(runId, 2, AgentStreamEventType.MODEL_STEP_STARTED,
                1, "", "", ""));

        MvcResult stream = mockMvc.perform(get(path() + "/" + runId + "/stream")
                        .with(authentication(authToken(VIEWER_ID, "viewer")))
                        .header("Last-Event-ID", runId + ":1")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        listener.get().onEvent(event(runId, 3, AgentStreamEventType.RUN_SUCCEEDED,
                0, "", "answer", ""));

        String replay = mockMvc.perform(asyncDispatch(stream)).andReturn()
                .getResponse().getContentAsString();
        assertThat(replay).doesNotContain("id:" + runId + ":1")
                .contains("id:" + runId + ":2", "id:" + runId + ":3");

        MvcResult terminalReplay = mockMvc.perform(get(path() + "/" + runId + "/stream")
                        .with(authentication(authToken(VIEWER_ID, "viewer")))
                        .header("Last-Event-ID", runId + ":2")
                        .accept(MediaType.TEXT_EVENT_STREAM))
                .andExpect(request().asyncStarted())
                .andReturn();
        assertThat(mockMvc.perform(asyncDispatch(terminalReplay)).andReturn()
                .getResponse().getContentAsString()).contains("event:run-succeeded");
    }

    @Test
    void sseEnforcesAuthenticationPermissionScopeAndLastEventId() throws Exception {
        captureStream(new AtomicBoolean());
        String runId = body(startAs(DEVELOPER_ID, "developer")).path("data").path("runId").asText();

        mockMvc.perform(get(path() + "/" + runId + "/stream"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(get(path() + "/" + runId + "/stream")
                        .with(authentication(authToken(OUTSIDER_ID, "outsider"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path(OTHER_PROJECT_ID) + "/" + runId + "/stream")
                        .with(authentication(authToken(OWNER_ID, "owner"))))
                .andExpect(status().isNotFound());
        mockMvc.perform(get(path() + "/" + runId + "/stream")
                        .with(authentication(authToken(VIEWER_ID, "viewer")))
                        .header("Last-Event-ID", "other-run:1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AGENT_0601"));
    }

    @Test
    void grpcFailureProjectsFailedAndPublishesStableKind() throws Exception {
        AtomicReference<AgentRuntimeEventListener> listener = captureStream(new AtomicBoolean());
        String runId = body(startAs(DEVELOPER_ID, "developer")).path("data").path("runId").asText();

        listener.get().onError(AgentRuntimeStreamFailureKind.DEADLINE_EXCEEDED);

        Map<String, Object> stored = jdbcTemplate.queryForMap(
                "SELECT status, failure_kind, final_output FROM dp_agent_run WHERE run_id=?", runId);
        assertThat(stored.get("status")).isEqualTo("FAILED");
        assertThat(stored.get("failure_kind")).isEqualTo("DEADLINE_EXCEEDED");
        assertThat(stored.get("final_output")).isNull();
    }

    @Test
    void readOnlyToolsUsePersistedRunScopeRealDataAndFreshRbac() throws Exception {
        captureStream(new AtomicBoolean());
        JsonNode run = body(startAs(DEVELOPER_ID, "developer")).path("data");
        String runId = run.path("runId").asText();
        String requestId = run.path("requestId").asText();
        jdbcTemplate.update("""
                INSERT INTO dp_task (
                    id, workspace_id, project_id, title, status, priority,
                    reporter_user_id, completed_at
                ) VALUES (300, ?, ?, 'Open integration task', 'TODO', 'HIGH', ?, NULL),
                         (301, ?, ?, 'Closed integration task', 'DONE', 'LOW', ?, CURRENT_TIMESTAMP(6))
                """, WORKSPACE_ID, PROJECT_ID, DEVELOPER_ID,
                WORKSPACE_ID, PROJECT_ID, DEVELOPER_ID);
        jdbcTemplate.update("""
                INSERT INTO dp_project_activity (
                    workspace_id, project_id, source_type, activity_type,
                    source_delivery_id, title, summary, occurred_at
                ) VALUES (?, ?, 'GITHUB', 'GITHUB_WEBHOOK_PING',
                          'agent-tool-integration', 'Recent integration activity',
                          'External project text', CURRENT_TIMESTAMP(6))
                """, WORKSPACE_ID, PROJECT_ID);

        var summary = executeTool(requestId, runId, "summary-call", "project.get_summary", Map.of());
        var tasks = executeTool(requestId, runId, "task-call", "task.list_open", Map.of("limit", 20));
        var activities = executeTool(requestId, runId, "activity-call",
                "project.list_recent_activity", Map.of("limit", 20));

        assertThat(summary.data()).containsEntry("projectKey", "AGENT")
                .containsEntry("external_untrusted_content", true);
        assertThat((List<?>) tasks.data().get("items")).hasSize(1);
        assertThat((List<?>) activities.data().get("items")).hasSize(1);

        jdbcTemplate.update("""
                UPDATE dp_project_member SET status='REMOVED'
                WHERE workspace_id=? AND project_id=? AND user_id=?
                """, WORKSPACE_ID, PROJECT_ID, DEVELOPER_ID);
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> executeTool(
                        requestId, runId, "after-revoke", "project.get_summary", Map.of()))
                .isInstanceOf(BusinessException.class);
    }

    private AtomicReference<AgentRuntimeEventListener> captureStream(AtomicBoolean transactionActive) {
        AtomicReference<AgentRuntimeEventListener> listener = new AtomicReference<>();
        doAnswer(invocation -> {
            transactionActive.set(TransactionSynchronizationManager.isActualTransactionActive());
            listener.set(invocation.getArgument(1));
            return null;
        }).when(streamingPort).stream(any(AgentRunCommand.class), any(AgentRuntimeEventListener.class));
        return listener;
    }

    private MvcResult startAs(long userId, String username) throws Exception {
        return mockMvc.perform(post(path())
                        .with(authentication(authToken(userId, username)))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsBytes(Map.of("input", "hello agent"))))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("RUNNING"))
                .andReturn();
    }

    private AgentStreamEvent event(String runId, long sequence, AgentStreamEventType type,
                                   int step, String toolName, String output, String failureKind) {
        return new AgentStreamEvent(runId + ":" + sequence, runId, sequence, type,
                step, toolName, output, failureKind);
    }

    private AgentToolResult executeTool(
            String requestId, String runId, String callId, String toolName,
            Map<String, Object> arguments) {
        return agentToolApplicationService.execute(
                new AgentToolCommand(requestId, runId, callId, toolName, arguments));
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
        jdbcTemplate.update("DELETE FROM dp_project_activity");
        jdbcTemplate.update("DELETE FROM dp_task");
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
