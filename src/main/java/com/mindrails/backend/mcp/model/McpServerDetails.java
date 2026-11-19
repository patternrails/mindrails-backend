package com.mindrails.backend.mcp.model;

import java.time.Instant;
import java.util.UUID;

/** Safe query result. Its connection settings have already been masked by the service. */
public record McpServerDetails(
        UUID id,
        UUID ownerId,
        String name,
        String transport,
        McpConnectionConfig config,
        boolean enabled,
        long version,
        Instant createdAt,
        Instant updatedAt,
        McpStatus status,
        int toolCount,
        Instant discoveredAt,
        boolean stale,
        boolean hasSecrets) {}
