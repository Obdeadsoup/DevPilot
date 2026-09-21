package com.obdeadsoup.devpilot.agent.persistence.mapper;

import com.obdeadsoup.devpilot.agent.persistence.entity.AgentRunEntity;
import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in test against the real Compose MySQL schema; all rows are rolled back. */
class AgentRunMapperMySqlTest {
    @Test
    void listCountStatusFilterAndOrderUseTheRealSchema() throws Exception {
        String password = System.getenv("DEVPILOT_MYSQL_PASSWORD");
        assumeTrue(password != null && !password.isBlank(), "Compose MySQL credentials unavailable");
        String database = System.getenv().getOrDefault("DEVPILOT_MYSQL_DATABASE", "devpilot");
        String username = System.getenv().getOrDefault("DEVPILOT_MYSQL_USERNAME", "devpilot");
        String url = "jdbc:mysql://mysql:3306/" + database + "?useSSL=false&serverTimezone=UTC";
        Configuration configuration = new Configuration(new Environment("compose-mysql",
                new JdbcTransactionFactory(), new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                url, username, password)));
        configuration.addMapper(AgentRunMapper.class);

        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(false)) {
            Connection connection = session.getConnection();
            assertThat(connection.getAutoCommit()).isFalse();
            try {
                long[] scope = firstProject(connection);
                long actorId = firstUser(connection);
                AgentRunMapper mapper = session.getMapper(AgentRunMapper.class);
                long initialTotal = mapper.countHistory(scope[0], scope[1], null);
                long initialFailed = mapper.countHistory(scope[0], scope[1], "FAILED");
                LocalDateTime base = LocalDateTime.of(2100, 1, 1, 0, 0);
                String oldest = insert(connection, scope, actorId, "RUNNING", base, false);
                String failed = insert(connection, scope, actorId, "FAILED", base.plusSeconds(1), false);
                String newest = insert(connection, scope, actorId, "RUNNING", base.plusSeconds(2), false);
                insert(connection, scope, actorId, "RUNNING", base.plusSeconds(3), true);
                // The JDBC inserts bypass MyBatis, so invalidate its earlier count cache.
                session.clearCache();

                List<AgentRunEntity> firstPage = mapper.findHistory(scope[0], scope[1], null, 0, 3);
                assertThat(firstPage).extracting(AgentRunEntity::getRunId)
                        .containsExactly(newest, failed, oldest);
                assertThat(firstPage).extracting(AgentRunEntity::getStartedAt)
                        .containsExactly(base.plusSeconds(2), base.plusSeconds(1), base);
                assertThat(mapper.countHistory(scope[0], scope[1], null)).isEqualTo(initialTotal + 3);
                assertThat(mapper.countHistory(scope[0], scope[1], "FAILED")).isEqualTo(initialFailed + 1);
                assertThat(mapper.findHistory(scope[0], scope[1], "FAILED", 0, 1))
                        .extracting(AgentRunEntity::getRunId).containsExactly(failed);
                assertThat(mapper.findHistory(scope[0], scope[1], null, 2, 1))
                        .extracting(AgentRunEntity::getRunId).containsExactly(oldest);
            } finally {
                // Direct JDBC writes do not set MyBatis SqlSession's dirty flag.
                connection.rollback();
            }
        }
    }

    private long[] firstProject(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT workspace_id, id FROM dp_project ORDER BY id LIMIT 1")) {
            assumeTrue(result.next(), "No project in Compose MySQL");
            return new long[] {result.getLong(1), result.getLong(2)};
        }
    }

    private long firstUser(Connection connection) throws Exception {
        try (var statement = connection.createStatement();
             ResultSet result = statement.executeQuery("SELECT id FROM dp_user ORDER BY id LIMIT 1")) {
            assumeTrue(result.next(), "No user in Compose MySQL");
            return result.getLong(1);
        }
    }

    private String insert(Connection connection, long[] scope, long actorId, String status,
                          LocalDateTime startedAt, boolean deleted) throws Exception {
        String runId = UUID.randomUUID().toString();
        try (PreparedStatement statement = connection.prepareStatement("""
                INSERT INTO dp_agent_run (run_id, request_id, workspace_id, project_id, created_by,
                    status, user_input, failure_kind, started_at, finished_at, deleted)
                VALUES (?, ?, ?, ?, ?, ?, 'MySQL history regression', ?, ?, ?, ?)
                """)) {
            statement.setString(1, runId);
            statement.setString(2, UUID.randomUUID().toString());
            statement.setLong(3, scope[0]);
            statement.setLong(4, scope[1]);
            statement.setLong(5, actorId);
            statement.setString(6, status);
            statement.setString(7, "FAILED".equals(status) ? "TOOL_ERROR" : null);
            statement.setObject(8, startedAt);
            statement.setObject(9, "FAILED".equals(status) ? startedAt.plusSeconds(1) : null);
            statement.setBoolean(10, deleted);
            statement.executeUpdate();
        }
        return runId;
    }
}
