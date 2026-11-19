package com.mindrails.backend.mcp.model;

import java.time.Instant;
import java.util.UUID;

/** Persisted definition; env and headers contain sealed values or environment references. */
public record McpServer(
        UUID id,
        UUID ownerId,
        String name,
        McpConnectionConfig config,
        boolean enabled,
        long version,
        Instant createdAt,
        Instant updatedAt) {
    public McpServer(UUID id, String name, McpConnectionConfig config, boolean enabled, long version, Instant createdAt, Instant updatedAt) {
        this(id, com.mindrails.backend.identity.ResourceAccess.creationOwner(), name, config, enabled, version, createdAt, updatedAt);
    }
}
