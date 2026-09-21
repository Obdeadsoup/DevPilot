package com.obdeadsoup.devpilot.knowledge.persistence.mapper;

import org.apache.ibatis.datasource.unpooled.UnpooledDataSource;
import org.apache.ibatis.logging.nologging.NoLoggingImpl;
import org.apache.ibatis.mapping.Environment;
import org.apache.ibatis.session.Configuration;
import org.apache.ibatis.session.SqlSessionFactoryBuilder;
import org.apache.ibatis.transaction.jdbc.JdbcTransactionFactory;
import org.junit.jupiter.api.Test;

import java.sql.ResultSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/** Opt-in read-only regression against READY chunks in the real Compose MySQL schema. */
class KnowledgeChunkMapperMySqlTest {
    @Test
    void retrievableQueryUsesValidSqlAndReturnsReadyChunks() throws Exception {
        String password = System.getenv("DEVPILOT_MYSQL_PASSWORD");
        assumeTrue(password != null && !password.isBlank(), "Compose MySQL credentials unavailable");
        String database = System.getenv().getOrDefault("DEVPILOT_MYSQL_DATABASE", "devpilot");
        String username = System.getenv().getOrDefault("DEVPILOT_MYSQL_USERNAME", "devpilot");
        String url = "jdbc:mysql://mysql:3306/" + database + "?useSSL=false&serverTimezone=UTC";
        Configuration configuration = new Configuration(new Environment("compose-mysql",
                new JdbcTransactionFactory(), new UnpooledDataSource("com.mysql.cj.jdbc.Driver",
                url, username, password)));
        configuration.setLogImpl(NoLoggingImpl.class);
        configuration.addMapper(KnowledgeChunkMapper.class);

        try (var session = new SqlSessionFactoryBuilder().build(configuration).openSession(true)) {
            long workspaceId;
            long projectId;
            try (var statement = session.getConnection().createStatement();
                 ResultSet result = statement.executeQuery("""
                         SELECT chunk.workspace_id, chunk.project_id
                         FROM dp_knowledge_chunk chunk
                         JOIN dp_knowledge_document document ON document.id=chunk.document_id
                         WHERE document.status='READY' AND document.deleted=0
                           AND chunk.access_scope='PROJECT_MEMBER'
                         LIMIT 1
                         """)) {
                assumeTrue(result.next(), "No READY project chunk in Compose MySQL");
                workspaceId = result.getLong(1);
                projectId = result.getLong(2);
            }
            var chunks = session.getMapper(KnowledgeChunkMapper.class)
                    .findRetrievableByProject(workspaceId, projectId, 20);
            assertThat(chunks).isNotEmpty().allSatisfy(chunk -> {
                assertThat(chunk.workspaceId()).isEqualTo(workspaceId);
                assertThat(chunk.projectId()).isEqualTo(projectId);
                assertThat(chunk.chunkText()).isNotBlank();
            });
        }
    }
}
