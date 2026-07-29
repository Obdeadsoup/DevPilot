package com.obdeadsoup.devpilot.identity;

import com.obdeadsoup.devpilot.identity.persistence.entity.UserEntity;
import com.obdeadsoup.devpilot.identity.persistence.mapper.UserMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.MySQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("integration-test")
@SpringBootTest
class IdentityUserPersistenceIntegrationTest {

    @Container
    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4")
            .withDatabaseName("devpilot_identity_persistence_test")
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
    private UserMapper userMapper;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void clearUsers() {
        jdbcTemplate.update("DELETE FROM dp_user");
    }

    @Test
    void flywayV3CreatesExpectedUserColumnsAndConstraints() {
        Integer appliedV3 = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM flyway_schema_history
                WHERE version = '3' AND success = 1
                """, Integer.class);
        Integer expectedColumns = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = DATABASE()
                  AND table_name = 'dp_user'
                  AND column_name IN (
                    'id', 'username', 'email', 'display_name', 'password_hash', 'status',
                    'created_at', 'updated_at', 'version', 'deleted'
                  )
                """, Integer.class);

        assertThat(appliedV3).isEqualTo(1);
        assertThat(expectedColumns).isEqualTo(10);

        insertUser(1L, "alice", "alice@example.com", "ACTIVE");
        assertThatThrownBy(() -> insertUser(2L, "alice", "second@example.com", "ACTIVE"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertUser(3L, "second", "alice@example.com", "ACTIVE"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> insertUser(4L, "UpperCase", "upper@example.com", "ACTIVE"))
                .isInstanceOf(RuntimeException.class);
    }

    @Test
    void mapperFindsActiveRecordByNormalizedUsernameOrEmail() {
        insertUser(42L, "alice", "alice@example.com", "ACTIVE");

        UserEntity byUsername = userMapper.findByNormalizedLogin("alice").orElseThrow();
        UserEntity byEmail = userMapper.findByNormalizedLogin("alice@example.com").orElseThrow();

        assertThat(byUsername).isEqualTo(byEmail);
        assertThat(byUsername.id()).isEqualTo(42L);
        assertThat(byUsername.passwordHash()).startsWith("{bcrypt}");
        assertThat(byUsername.deleted()).isFalse();
    }

    private void insertUser(long id, String username, String email, String status) {
        jdbcTemplate.update("""
                INSERT INTO dp_user (
                    id, username, email, display_name, password_hash, status
                ) VALUES (?, ?, ?, 'Test User', ?, ?)
                """,
                id,
                username,
                email,
                passwordEncoder.encode("integration-test-password"),
                status
        );
    }
}
