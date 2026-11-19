package com.mindrails.backend.mcp.model;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** A persisted discovery snapshot tied to one configuration version. */
public record McpToolCatalog(long version, Instant discoveredAt, List<Map<String, Object>> tools) {
    public McpToolCatalog {
        tools = List.copyOf(tools);
    }
}
