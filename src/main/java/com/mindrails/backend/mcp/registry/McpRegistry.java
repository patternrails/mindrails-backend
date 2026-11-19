package com.mindrails.backend.mcp.registry;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.mcp.model.McpServer;
import com.mindrails.backend.mcp.model.McpToolCatalog;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/** Stores definitions and versioned discovery snapshots. No network operations belong here. */
public interface McpRegistry {
    List<McpServer> list();

    Optional<McpServer> find(UUID id);

    /** Atomically saves the batch. An absent expected version means insert only. */
    ServiceResult<Void> saveAll(
            List<McpServer> servers, Map<UUID, Long> expectedVersions, int maxServers);

    ServiceResult<Void> remove(UUID id, long expectedVersion);

    Optional<McpToolCatalog> findCatalog(UUID id);

    /** Ignores discovery results for a definition that has been deleted or changed. */
    void saveCatalog(UUID id, McpToolCatalog catalog);
}
