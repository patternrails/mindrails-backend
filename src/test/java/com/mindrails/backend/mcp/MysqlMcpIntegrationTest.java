package com.mindrails.backend.mcp;

import static com.mindrails.backend.mcp.McpRuntimeTest.success;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.mcp.model.McpServer;
import com.mindrails.backend.mcp.model.McpTool;
import com.mindrails.backend.mcp.model.McpToolCatalog;
import com.mindrails.backend.mcp.registry.MysqlMcpRegistry;
import com.mindrails.backend.mcp.service.DefaultMcpService;
import com.mindrails.backend.mcp.service.internal.McpClientManager;
import com.mindrails.backend.mcp.service.internal.McpConfigParser;
import com.mindrails.backend.mcp.service.internal.McpProperties;
import com.mindrails.backend.mcp.service.internal.McpSecrets;
import com.mindrails.backend.metrics.TestMetrics;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import com.mindrails.backend.mcp.service.McpToolAuthorization;

class MysqlMcpIntegrationTest {
    @Test
    void registryAndToolCatalogSurviveRecreationAndBatchFailureRollsBack() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:mysql://127.0.0.1:3306/mindrails",
                        "mindrails_app",
                        System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        // Integration tests require the same migrated schema used by the application.
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mcp_registry_lock WHERE id=1", Integer.class));
        var registry = new MysqlMcpRegistry(jdbc);
        var properties = new McpProperties();
        var secrets = new McpSecrets("");
        try (var manager =
                new McpClientManager(registry, secrets, properties, TestMetrics.disabled())) {
            var service =
                    new DefaultMcpService(
                            registry, manager, secrets, properties, TestHistory.disabled(),
                            org.mockito.Mockito.mock(
                                    McpToolAuthorization.class, invocation -> null));
            String name = "mcp-test-" + UUID.randomUUID();
            UUID id = null;
            try {
                var server =
                        success(
                                        service.importConfig(
                                                Map.of(
                                                        "mcpServers",
                                                        Map.of(
                                                                name,
                                                                Map.of(
                                                                        "url",
                                                                        "http://127.0.0.1:8765/mcp"))),
                                                "CREATE_ONLY",
                                                null))
                                .items()
                                .getFirst();
                id = server.id();
                registry.saveCatalog(
                        id,
                        new McpToolCatalog(
                                0,
                                Instant.now(),
                                List.of(
                                        Map.of(
                                                "name",
                                                "echo",
                                                "modelName",
                                                McpTool.modelName(id, "echo"),
                                                "inputSchema",
                                                Map.of("type", "object")))));
                var recovered = new MysqlMcpRegistry(new JdbcTemplate(source));
                assertEquals(id, recovered.find(id).orElseThrow().id());
                assertEquals(server.config(), recovered.find(id).orElseThrow().config());
                assertEquals(1, recovered.findCatalog(id).orElseThrow().tools().size());
                int before = registry.list().size();
                assertInstanceOf(
                        ServiceResult.Failure.class,
                        service.importConfig(
                                Map.of(
                                        "mcpServers",
                                        Map.of(
                                                name,
                                                Map.of("url", "http://localhost/new"),
                                                name + "-other",
                                                Map.of("command", "python3"))),
                                "CREATE_ONLY",
                                null));
                assertEquals(before, recovered.list().size());
                var updated = success(service.update(id, 0, null, false));
                assertEquals(1, updated.version());
                assertFalse(recovered.find(id).orElseThrow().enabled());
                assertInstanceOf(
                        ServiceResult.Failure.class, service.update(updated.id(), 0, null, true));
                success(service.delete(id, 1));
                assertTrue(recovered.find(id).isEmpty());
                assertTrue(recovered.findCatalog(id).isEmpty());
                id = null;
            } finally {
                if (id != null) {
                    jdbc.update("DELETE FROM mcp_servers WHERE id=?", id.toString());
                }
            }
        }
    }

    @Test
    void separateRegistryInstancesSerializeCapacityChecksAndVersionedWrites() throws Exception {
        var jdbc = database();
        var left = new MysqlMcpRegistry(jdbc);
        var right = new MysqlMcpRegistry(new JdbcTemplate(jdbc.getDataSource()));
        int before = left.list().size();
        var first = server(UUID.randomUUID(), "mcp-race-" + UUID.randomUUID(), 0);
        var second = server(UUID.randomUUID(), "mcp-race-" + UUID.randomUUID(), 0);
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<ServiceResult<Void>> saveLeft =
                    () -> {
                        barrier.await(2, TimeUnit.SECONDS);
                        return left.saveAll(List.of(first), Map.of(), before + 1);
                    };
            Callable<ServiceResult<Void>> saveRight =
                    () -> {
                        barrier.await(2, TimeUnit.SECONDS);
                        return right.saveAll(List.of(second), Map.of(), before + 1);
                    };
            long successes = 0;
            for (var result : workers.invokeAll(List.of(saveLeft, saveRight))) {
                if (result.get(5, TimeUnit.SECONDS) instanceof ServiceResult.Success<?>) {
                    successes++;
                }
            }
            assertEquals(1, successes);
            assertEquals(before + 1, left.list().size());
            var stored =
                    left.find(first.id()).orElseGet(() -> left.find(second.id()).orElseThrow());
            var updated = server(stored.id(), stored.name(), 1);
            Callable<ServiceResult<Void>> update =
                    () -> {
                        barrier.await(2, TimeUnit.SECONDS);
                        return new MysqlMcpRegistry(new JdbcTemplate(jdbc.getDataSource()))
                                .saveAll(List.of(updated), Map.of(stored.id(), 0L), before + 1);
                    };
            successes = 0;
            for (var result : workers.invokeAll(List.of(update, update))) {
                if (result.get(5, TimeUnit.SECONDS) instanceof ServiceResult.Success<?>) {
                    successes++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, left.find(stored.id()).orElseThrow().version());
        } finally {
            jdbc.update(
                    "DELETE FROM mcp_servers WHERE id IN (?,?)",
                    first.id().toString(),
                    second.id().toString());
        }
    }

    @Test
    void databaseFailureRollsBackEarlierWritesInTheBatch() throws Exception {
        var jdbc = database();
        var registry = new MysqlMcpRegistry(jdbc);
        var valid = server(UUID.randomUUID(), "mcp-rollback-" + UUID.randomUUID(), 0);
        var invalid = server(UUID.randomUUID(), "x".repeat(129), 0);
        try {
            int capacity = registry.list().size() + 2;
            assertThrows(
                    DataAccessException.class,
                    () -> registry.saveAll(List.of(valid, invalid), Map.of(), capacity));
            assertTrue(registry.find(valid.id()).isEmpty());
            assertTrue(registry.find(invalid.id()).isEmpty());
        } finally {
            jdbc.update(
                    "DELETE FROM mcp_servers WHERE id IN (?,?)",
                    valid.id().toString(),
                    invalid.id().toString());
        }
    }

    private JdbcTemplate database() throws Exception {
        var source =
                new DriverManagerDataSource(
                        "jdbc:mysql://127.0.0.1:3306/mindrails",
                        "mindrails_app",
                        System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        var jdbc = new JdbcTemplate(source);
        // Integration tests require the same migrated schema used by the application.
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM mcp_registry_lock WHERE id=1", Integer.class));
        return jdbc;
    }

    private McpServer server(UUID id, String name, long version) {
        var config =
                new McpConfigParser()
                        .normalize("server", Map.of("url", "http://localhost/mcp"))
                        .config();
        return new McpServer(id, name, config, true, version, Instant.now(), Instant.now());
    }
}
