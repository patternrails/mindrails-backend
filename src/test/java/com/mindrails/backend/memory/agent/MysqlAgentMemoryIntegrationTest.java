package com.mindrails.backend.memory.agent;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.AdditionalAnswers.delegatesTo;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.embedding.QdrantProperties;

import java.sql.DriverManager;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

import tools.jackson.databind.json.JsonMapper;

class MysqlAgentMemoryIntegrationTest {
    @Test
    @SuppressWarnings("unchecked")
    void offlineImportReadsLegacyRecordsAndPreservesIdentityAndVersion() throws Exception {
        String url = System.getenv().getOrDefault("MINDRAILS_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/mindrails");
        String user = System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME", "mindrails_app");
        String password = System.getenv("MINDRAILS_MYSQL_PASSWORD");
        try (var connection = DriverManager.getConnection(url, user, password)) {
            var jdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
            // Shadow any legacy table on this connection so the fixture never reads or writes user records.
            jdbc.execute("""
                    CREATE TEMPORARY TABLE agent_memory_entries (
                        id CHAR(36) PRIMARY KEY, agent_id CHAR(36), memory_id CHAR(36),
                        entry_key VARCHAR(128), type VARCHAR(32), content TEXT, tags JSON,
                        pinned BOOLEAN, priority INT, source_session_id CHAR(36),
                        created_at TIMESTAMP(6), updated_at TIMESTAMP(6), version BIGINT
                    )
                    """);
            var created = Instant.parse("2026-01-01T00:00:00Z");
            var updated = created.plusSeconds(10);
            var snapshot = new AgentMemoryEntry(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                    "migration.fact", AgentMemoryEntry.Type.FACT, "Revised migration fact", List.of("test"),
                    true, 1, UUID.randomUUID(), created, updated, 2);
            jdbc.update("INSERT INTO agent_memory_entries VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                    snapshot.id().toString(), snapshot.agentId().toString(), snapshot.memoryId().toString(),
                    snapshot.key(), snapshot.type().name(), snapshot.content(), "[\"test\"]", snapshot.pinned(),
                    snapshot.priority(), snapshot.sourceSessionId().toString(), Timestamp.from(created),
                    Timestamp.from(updated), snapshot.version());

            var agents = mock(AgentRegistry.class);
            when(agents.findManaged(snapshot.agentId())).thenReturn(Optional.of(
                    Agent.builder().id(snapshot.agentId()).memoryId(snapshot.memoryId()).build()));
            var data = new InMemoryAgentMemoryStore();
            var target = mock(QdrantAgentMemoryStore.class, delegatesTo(data));
            ObjectProvider<JdbcTemplate> sql = mock(ObjectProvider.class);
            when(sql.getObject()).thenReturn(jdbc);
            var env = new MockEnvironment()
                    .withProperty("spring.main.web-application-type", "none")
                    .withProperty("mindrails.agent-memory.migrate.source", "mysql")
                    .withProperty("mindrails.agent-memory.migrate.agent-id", snapshot.agentId().toString());
            var context = mock(ConfigurableApplicationContext.class);
            var command = new AgentMemoryMigration(target, new AgentMemoryProperties(), new QdrantProperties(),
                    sql, JsonMapper.builder().build(), agents, env, context);

            command.run(new DefaultApplicationArguments());
            command.run(new DefaultApplicationArguments());

            assertEquals(List.of(snapshot), data.entries(snapshot.memoryId()));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM agent_memory_entries", Integer.class));
            assertEquals(2L, jdbc.queryForObject("SELECT version FROM agent_memory_entries", Long.class));
            verify(context, times(2)).close();
        }
    }
}
