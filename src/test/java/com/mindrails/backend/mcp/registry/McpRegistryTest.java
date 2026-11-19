package com.mindrails.backend.mcp.registry;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.mcp.model.McpServer;
import com.mindrails.backend.mcp.service.internal.McpConfigParser;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

class McpRegistryTest {
    @Test
    void concurrentImportsCannotExceedTheServerLimit() throws Exception {
        var registry = new InMemoryMcpRegistry();
        var barrier = new CyclicBarrier(2);
        try (var workers = Executors.newFixedThreadPool(2)) {
            Callable<ServiceResult<Void>> save =
                    () -> {
                        var candidate = server(UUID.randomUUID(), UUID.randomUUID().toString(), 0);
                        barrier.await(2, TimeUnit.SECONDS);
                        return registry.saveAll(List.of(candidate), Map.of(), 1);
                    };
            var results = workers.invokeAll(List.of(save, save));
            long successes = 0;
            for (var result : results) {
                if (result.get(2, TimeUnit.SECONDS) instanceof ServiceResult.Success<?>) {
                    successes++;
                }
            }
            assertEquals(1, successes);
            assertEquals(1, registry.list().size());
        }
    }

    @Test
    void aConflictLateInABatchDoesNotSaveEarlierEntriesAndVersionsCannotBeOverwritten() {
        var registry = new InMemoryMcpRegistry();
        var original = server(UUID.randomUUID(), "original", 0);
        assertInstanceOf(
                ServiceResult.Success.class, registry.saveAll(List.of(original), Map.of(), 50));
        var added = server(UUID.randomUUID(), "added", 0);
        var conflicting = server(UUID.randomUUID(), "original", 0);
        assertInstanceOf(
                ServiceResult.Failure.class,
                registry.saveAll(List.of(added, conflicting), Map.of(), 50));
        assertEquals(List.of(original), registry.list());
        var updated = server(original.id(), original.name(), 1);
        assertInstanceOf(
                ServiceResult.Success.class,
                registry.saveAll(List.of(updated), Map.of(original.id(), 0L), 50));
        assertInstanceOf(
                ServiceResult.Failure.class,
                registry.saveAll(List.of(added, updated), Map.of(original.id(), 0L), 50));
        assertEquals(List.of(updated), registry.list());
        assertInstanceOf(ServiceResult.Failure.class, registry.remove(original.id(), 0));
        assertTrue(registry.find(original.id()).isPresent());
    }

    private McpServer server(UUID id, String name, long version) {
        var config =
                new McpConfigParser()
                        .normalize(name, Map.of("url", "http://localhost/mcp"))
                        .config();
        return new McpServer(id, name, config, true, version, Instant.now(), Instant.now());
    }
}
