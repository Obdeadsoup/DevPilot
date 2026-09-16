package com.obdeadsoup.devpilot.knowledge;

import com.obdeadsoup.devpilot.identity.domain.DevPilotUserPrincipal;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class KnowledgeBaseIntegrationTest {
    private static final long OWNER_ID = 1L;
    private static final long VIEWER_ID = 2L;
    private static final long OUTSIDER_ID = 3L;
    private static final long WORKSPACE_ID = 100L;
    private static final long PROJECT_ID = 200L;
    private static final long OTHER_PROJECT_ID = 201L;

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_knowledge_test")
            .withUsername("devpilot")
            .withPassword("devpilot_test_password");

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", MYSQL::getJdbcUrl);
        registry.add("spring.datasource.username", MYSQL::getUsername);
        registry.add("spring.datasource.password", MYSQL::getPassword);
        registry.add("devpilot.knowledge.local-directory", () -> ".runtime/knowledge-integration-objects");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc.update("DELETE FROM dp_knowledge_query_trace");
        jdbc.update("DELETE FROM dp_knowledge_chunk");
        jdbc.update("DELETE FROM dp_knowledge_document");
        jdbc.update("DELETE FROM dp_project_member");
        jdbc.update("DELETE FROM dp_workspace_member");
        jdbc.update("DELETE FROM dp_project");
        jdbc.update("DELETE FROM dp_workspace");
        jdbc.update("DELETE FROM dp_user");
        user(OWNER_ID, "owner");
        user(VIEWER_ID, "viewer");
        user(OUTSIDER_ID, "outsider");
        jdbc.update("INSERT INTO dp_workspace (id,name,slug,owner_user_id,status) VALUES (?,?,?,?, 'ACTIVE')",
                WORKSPACE_ID, "RAG Workspace", "rag-workspace", OWNER_ID);
        jdbc.update("INSERT INTO dp_workspace_member (workspace_id,user_id,role,status,invited_by,joined_at) "
                        + "VALUES (?,?, 'MEMBER','ACTIVE',?,CURRENT_TIMESTAMP(6))",
                WORKSPACE_ID, VIEWER_ID, OWNER_ID);
        project(PROJECT_ID, "DEV", "DevPilot");
        project(OTHER_PROJECT_ID, "OTHER", "Other Project");
        jdbc.update("INSERT INTO dp_project_member (workspace_id,project_id,user_id,role,status,created_by) "
                        + "VALUES (?,?,?,'VIEWER','ACTIVE',?)",
                WORKSPACE_ID, PROJECT_ID, VIEWER_ID, OWNER_ID);
    }

    @Test
    void uploadAsyncIngestHybridSearchAndAclFormAClosedLoop() throws Exception {
        upload(PROJECT_ID, "architecture.md", """
                # Outbox architecture
                DevPilot uses the transactional Outbox pattern to persist domain changes and events atomically.
                The dispatcher retries failed events with exponential backoff and moves exhausted events to dead state.
                """).andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("UPLOADED"));
        upload(OTHER_PROJECT_ID, "private.md", "ForbiddenSecretZephyr belongs only to another project.")
                .andExpect(status().isAccepted());

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            assertThat(statuses()).containsOnly("READY");
            assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_knowledge_chunk", Integer.class)).isEqualTo(2);
        });

        mockMvc.perform(get(path(PROJECT_ID) + "/documents").with(authentication(auth(OWNER_ID, "owner"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].filename").value("architecture.md"))
                .andExpect(jsonPath("$.data[0].chunkCount").value(1));

        MvcResult result = mockMvc.perform(post(path(PROJECT_ID) + "/search")
                        .with(authentication(auth(OWNER_ID, "owner")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"query":"那一直失败怎么办？", "conversationHistory":["DevPilot 为什么使用 Outbox？"], "topK":5}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.rewrittenQuery").value(
                        "DevPilot 为什么使用 Outbox？\nFollow-up question: 那一直失败怎么办？"))
                .andExpect(jsonPath("$.data.hits[0].sourceFile").value("architecture.md"))
                .andExpect(jsonPath("$.data.hits[0].content").value(org.hamcrest.Matchers.containsString("exponential backoff")))
                .andReturn();
        assertThat(result.getResponse().getContentAsString()).doesNotContain("ForbiddenSecretZephyr");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM dp_knowledge_query_trace", Integer.class)).isEqualTo(1);

        mockMvc.perform(get(path(PROJECT_ID) + "/documents").with(authentication(auth(VIEWER_ID, "viewer"))))
                .andExpect(status().isOk());
        mockMvc.perform(multipart(path(PROJECT_ID) + "/documents")
                        .file(file("viewer.md", "not allowed"))
                        .with(authentication(auth(VIEWER_ID, "viewer"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(get(path(PROJECT_ID) + "/documents").with(authentication(auth(OUTSIDER_ID, "outsider"))))
                .andExpect(status().isForbidden());
        mockMvc.perform(post(path(PROJECT_ID) + "/search")
                        .with(authentication(auth(OUTSIDER_ID, "outsider")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"query\":\"Outbox\"}"))
                .andExpect(status().isForbidden());
    }

    private org.springframework.test.web.servlet.ResultActions upload(long projectId, String name, String content)
            throws Exception {
        return mockMvc.perform(multipart(path(projectId) + "/documents")
                .file(file(name, content)).with(authentication(auth(OWNER_ID, "owner"))));
    }

    private MockMultipartFile file(String name, String content) {
        return new MockMultipartFile("file", name, "text/markdown", content.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private List<String> statuses() {
        return jdbc.queryForList("SELECT status FROM dp_knowledge_document ORDER BY id", String.class);
    }

    private UsernamePasswordAuthenticationToken auth(long id, String username) {
        return new UsernamePasswordAuthenticationToken(
                new DevPilotUserPrincipal(id, username, username + "@example.com", username), null, List.of());
    }

    private String path(long projectId) {
        return "/api/v1/workspaces/" + WORKSPACE_ID + "/projects/" + projectId + "/knowledge";
    }

    private void user(long id, String username) {
        jdbc.update("INSERT INTO dp_user (id,username,email,display_name,password_hash,status) "
                        + "VALUES (?,?,?,?, '{noop}not-used','ACTIVE')",
                id, username, username + "@example.com", username);
    }

    private void project(long id, String key, String name) {
        jdbc.update("INSERT INTO dp_project (id,workspace_id,name,project_key,status,visibility,created_by) "
                        + "VALUES (?,?,?,?,'ACTIVE','PRIVATE',?)",
                id, WORKSPACE_ID, name, key, OWNER_ID);
    }
}
