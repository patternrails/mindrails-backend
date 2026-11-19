package com.mindrails.backend.mcp.model;

import dev.langchain4j.agent.tool.ToolSpecification;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;

/** An executable tool snapshot. It never exposes a live client or transport. */
public record McpTool(
        UUID serverId, String serverName, long version, ToolSpecification specification) {
    public String modelName() {
        return modelName(serverId, specification.name());
    }

    public static String modelName(UUID serverId, String name) {
        String safe = name.replaceAll("[^A-Za-z0-9_]", "_");
        safe = safe.substring(0, Math.min(17, safe.length()));
        try {
            byte[] digest =
                    MessageDigest.getInstance("SHA-256")
                            .digest(name.getBytes(StandardCharsets.UTF_8));
            String hash = HexFormat.of().formatHex(digest).substring(0, 8);
            return "mcp_" + serverId.toString().replace("-", "") + "_" + safe + "_" + hash;
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }
}
