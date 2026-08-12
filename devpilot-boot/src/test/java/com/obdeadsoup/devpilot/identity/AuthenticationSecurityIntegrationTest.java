package com.obdeadsoup.devpilot.identity;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
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

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("identity-integration-test")
@AutoConfigureMockMvc
@SpringBootTest
class AuthenticationSecurityIntegrationTest {

    private static final String TEST_PASSWORD = "test-password-42!";
    private static final String TEST_USERNAME = "alice";
    private static final String TEST_EMAIL = "alice@example.com";

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_identity_test")
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
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM dp_user");
        try (RedisConnection connection = Objects.requireNonNull(redisTemplate.getConnectionFactory())
                .getConnection()) {
            connection.serverCommands().flushDb();
        }
        insertUser(42L, TEST_USERNAME, TEST_EMAIL, "Alice", "ACTIVE", TEST_PASSWORD);
    }

    @Test
    void readinessUsesLiveDatabaseAndRedisWhileLivenessStaysApplicationOnly() throws Exception {
        mockMvc.perform(get("/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("mysql"))));
        mockMvc.perform(get("/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("\"status\":\"UP\"")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("password"))));
    }

    @Test
    void flywayCreatesNormalizedUserTableWithUniqueUsernameAndEmail() {
        Integer tableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = DATABASE() AND table_name = 'dp_user'
                """, Integer.class);
        Integer uniqueIndexCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(DISTINCT index_name)
                FROM information_schema.statistics
                WHERE table_schema = DATABASE()
                  AND table_name = 'dp_user'
                  AND non_unique = 0
                  AND index_name IN ('uk_user_username', 'uk_user_email')
                """, Integer.class);
        Integer passwordHashLength = jdbcTemplate.queryForObject("""
                SELECT character_maximum_length
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'dp_user'
                  AND column_name = 'password_hash'
                """, Integer.class);

        assertThat(tableCount).isEqualTo(1);
        assertThat(uniqueIndexCount).isEqualTo(2);
        assertThat(passwordHashLength).isEqualTo(255);
        assertThatThrownBy(() -> insertUser(
                43L, TEST_USERNAME, "other@example.com", "Other", "ACTIVE", TEST_PASSWORD
        )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertUser(
                44L, "other", TEST_EMAIL, "Other", "ACTIVE", TEST_PASSWORD
        )).isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertUser(
                45L, "UpperCase", "upper@example.com", "Upper", "ACTIVE", TEST_PASSWORD
        )).isInstanceOf(RuntimeException.class);
    }

    @Test
    void unauthenticatedProtectedEndpointsReturnJson401WithoutRedirectOrHtml() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("IDENTITY_0401"))
                .andExpect(header().doesNotExist(HttpHeaders.LOCATION))
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("<html", "<!DOCTYPE"));

        mockMvc.perform(get("/api/v1/workspaces/100/projects/200/activities"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("IDENTITY_0401"));

        mockMvc.perform(get("/api/v1/notifications/stream"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0401"));
    }

    @Test
    void healthAndWebhookRemainPublic() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(post("/api/v1/github/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("GITHUB_0400"));
    }

    @Test
    void wrongPasswordAndUnknownUserHaveIdenticalExternalResponse() throws Exception {
        MvcResult wrongPassword = login(TEST_USERNAME, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0402"))
                .andReturn();
        MvcResult unknownUser = login("missing-user", "wrong-password")
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0402"))
                .andReturn();

        assertThat(unknownUser.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString());
    }

    @Test
    void lockedAndDisabledAccountsUseSameGenericCredentialFailure() throws Exception {
        MvcResult wrongPassword = login(TEST_USERNAME, "wrong-password")
                .andExpect(status().isUnauthorized())
                .andReturn();

        jdbcTemplate.update("UPDATE dp_user SET status = 'LOCKED' WHERE id = 42");
        MvcResult locked = login(TEST_USERNAME, TEST_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn();
        jdbcTemplate.update("UPDATE dp_user SET status = 'DISABLED' WHERE id = 42");
        MvcResult disabled = login(TEST_USERNAME, TEST_PASSWORD)
                .andExpect(status().isUnauthorized())
                .andReturn();

        assertThat(locked.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString());
        assertThat(disabled.getResponse().getContentAsString())
                .isEqualTo(wrongPassword.getResponse().getContentAsString());
    }

    @Test
    void successfulUsernameOrEmailLoginReturnsBearerTokenWithoutPasswordHash() throws Exception {
        MvcResult usernameLogin = login("  ALICE ", TEST_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.tokenType").value("Bearer"))
                .andExpect(jsonPath("$.data.expiresInSeconds").value(7200))
                .andExpect(jsonPath("$.data.user.id").value(42))
                .andExpect(jsonPath("$.data.user.username").value(TEST_USERNAME))
                .andExpect(jsonPath("$.data.user.email").value(TEST_EMAIL))
                .andReturn();
        MvcResult emailLogin = login("Alice@Example.COM", TEST_PASSWORD)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.accessToken").isNotEmpty())
                .andReturn();

        assertThat(accessToken(usernameLogin)).isNotEqualTo(accessToken(emailLogin));
        assertThat(usernameLogin.getResponse().getContentAsString())
                .doesNotContain("password", "passwordHash", "$2a$");
    }

    @Test
    void validBearerTokenAccessesCurrentUserAndUnknownRouteIsDenied() throws Exception {
        String accessToken = accessToken(login(TEST_USERNAME, TEST_PASSWORD)
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").value(42))
                .andExpect(jsonPath("$.data.username").value(TEST_USERNAME))
                .andExpect(jsonPath("$.data.displayName").value("Alice"))
                .andExpect(jsonPath("$.data.email").doesNotExist())
                .andExpect(result -> assertThat(result.getResponse().getContentAsString())
                        .doesNotContain("password", "passwordHash"));

        mockMvc.perform(get("/api/v1/not-defined")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("IDENTITY_0404"));
    }

    @Test
    void bearerTokenEstablishesNotificationSseAndReceivesConnectedEnvelope() throws Exception {
        String accessToken = accessToken(login(TEST_USERNAME, TEST_PASSWORD)
                .andExpect(status().isOk())
                .andReturn());

        MvcResult result = mockMvc.perform(get("/api/v1/notifications/stream")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(request().asyncStarted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM))
                .andReturn();

        assertThat(result.getResponse().getContentAsString())
                .contains("event:connected", "\"connected\":true", "\"unreadCount\":0")
                .doesNotContain("accessToken", "passwordHash");
    }

    @Test
    void malformedMissingAndExpiredBearerTokensReturnJson401() throws Exception {
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Token invalid"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0403"));
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer token with spaces"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0403"));
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer missing-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0403"));

        String accessToken = accessToken(login(TEST_USERNAME, TEST_PASSWORD)
                .andExpect(status().isOk())
                .andReturn());
        redisTemplate.expire(redisKey(accessToken), Duration.ZERO);
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0403"));
    }

    @Test
    void logoutRevokesCurrentBearerTokenImmediately() throws Exception {
        String accessToken = accessToken(login(TEST_USERNAME, TEST_PASSWORD)
                .andExpect(status().isOk())
                .andReturn());

        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("COMMON_0000"));
        mockMvc.perform(get("/api/v1/auth/me")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("IDENTITY_0403"));
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void blankOrOversizedLoginInputIsRejectedBeforeAuthentication() throws Exception {
        login(" ", TEST_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_0400"));
        login("x".repeat(255), TEST_PASSWORD)
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_0400"));
        login(TEST_USERNAME, "x".repeat(73))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_0400"));
    }

    private org.springframework.test.web.servlet.ResultActions login(String login, String password)
            throws Exception {
        return mockMvc.perform(post("/api/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(Map.of(
                        "login", login,
                        "password", password
                ))));
    }

    private String accessToken(MvcResult result) throws Exception {
        JsonNode response = objectMapper.readTree(result.getResponse().getContentAsByteArray());
        return response.path("data").path("accessToken").asText();
    }

    private String redisKey(String accessToken) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(accessToken.getBytes(StandardCharsets.UTF_8));
        return "devpilot:auth:access:" + HexFormat.of().formatHex(digest);
    }

    private void insertUser(
            long id,
            String username,
            String email,
            String displayName,
            String status,
            String rawPassword
    ) {
        jdbcTemplate.update("""
                INSERT INTO dp_user (
                    id, username, email, display_name, password_hash, status
                ) VALUES (?, ?, ?, ?, ?, ?)
                """,
                id,
                username,
                email,
                displayName,
                passwordEncoder.encode(rawPassword),
                status
        );
    }
}
