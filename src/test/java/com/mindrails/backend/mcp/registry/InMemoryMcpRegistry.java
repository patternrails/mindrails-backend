package com.mindrails.backend.mcp.registry;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.mcp.model.McpServer;
import com.mindrails.backend.mcp.model.McpToolCatalog;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import com.mindrails.backend.identity.ResourceAccess;

public final class InMemoryMcpRegistry implements McpRegistry {
    private final Map<UUID, McpServer> servers = new LinkedHashMap<>();
    private final Map<UUID, McpToolCatalog> catalogs = new HashMap<>();

    @Override
    public synchronized List<McpServer> list() {
        return servers.values().stream().filter(s -> ResourceAccess.visible(s.ownerId()))
                .sorted(Comparator.comparing(McpServer::name).thenComparing(McpServer::id))
                .toList();
    }

    @Override
    public synchronized Optional<McpServer> find(UUID id) {
        return Optional.ofNullable(servers.get(id)).filter(s -> ResourceAccess.visible(s.ownerId()));
    }

    @Override
    public synchronized ServiceResult<Void> saveAll(
            List<McpServer> batch, Map<UUID, Long> expectedVersions, int maxServers) {
        var next = new LinkedHashMap<>(servers);
        for (var server : batch) {
            ResourceAccess.requireOwner(server.ownerId());
            var old = servers.get(server.id());
            if (old != null) ResourceAccess.requireBinding(old.ownerId(), server.ownerId());
            Long expected = expectedVersions.get(server.id());
            if (expected != null && old == null) {
                return ServiceResult.failure(ErrorCode.NOT_FOUND, "MCP server not found");
            }
            if (expected == null && old != null || expected != null && old.version() != expected) {
                return ServiceResult.failure(
                        ErrorCode.CONFLICT, "MCP server was edited; refresh before retrying");
            }
            boolean duplicate =
                    next.values().stream()
                            .anyMatch(
                                    item ->
                                            ResourceAccess.sameOwner(item.ownerId(), server.ownerId()) && item.name().equals(server.name())
                                                    && !item.id().equals(server.id()));
            if (duplicate) {
                return ServiceResult.failure(
                        ErrorCode.CONFLICT, "Server already exists: " + server.name());
            }
            next.put(server.id(), server);
        }
        if (next.values().stream().filter(s -> ResourceAccess.sameOwner(s.ownerId(), batch.isEmpty() ? ResourceAccess.creationOwner() : batch.getFirst().ownerId())).count() > maxServers) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "MCP server limit exceeded");
        }
        servers.clear();
        servers.putAll(next);
        return ServiceResult.success();
    }

    @Override
    public synchronized ServiceResult<Void> remove(UUID id, long expectedVersion) {
        var old = find(id).orElse(null);
        if (old == null) {
            return ServiceResult.failure(ErrorCode.NOT_FOUND, "MCP server not found");
        }
        if (old.version() != expectedVersion) {
            return ServiceResult.failure(
                    ErrorCode.CONFLICT, "MCP server was edited; refresh before retrying");
        }
        servers.remove(id);
        catalogs.remove(id);
        return ServiceResult.success();
    }

    @Override
    public synchronized Optional<McpToolCatalog> findCatalog(UUID id) {
        return find(id).isEmpty() ? Optional.empty() : Optional.ofNullable(catalogs.get(id));
    }

    @Override
    public synchronized void saveCatalog(UUID id, McpToolCatalog catalog) {
        if (find(id).filter(server -> server.version() == catalog.version()).isPresent()) {
            catalogs.put(id, catalog);
        }
    }
}
