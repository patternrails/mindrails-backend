package com.mindrails.backend.mcp.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Normalized connection settings. Only vendor extensions and tool schemas remain dynamic. */
public record McpConnectionConfig(
        String type,
        String command,
        List<String> args,
        Map<String, String> env,
        String cwd,
        String url,
        Map<String, String> headers,
        long timeoutMs,
        String protocolVersion,
        Map<String, Object> extensions) {

    private static final Set<String> FIELDS =
            Set.of(
                    "type",
                    "command",
                    "args",
                    "env",
                    "cwd",
                    "url",
                    "headers",
                    "timeoutMs",
                    "protocolVersion");

    public McpConnectionConfig {
        args = args == null ? List.of() : List.copyOf(args);
        env = env == null ? Map.of() : Map.copyOf(env);
        headers = headers == null ? Map.of() : Map.copyOf(headers);
        extensions = Collections.unmodifiableMap(new LinkedHashMap<>(extensions));
    }

    /** Reads already validated settings from the parser or the persisted JSON document. */
    @JsonCreator
    @SuppressWarnings("unchecked")
    public static McpConnectionConfig fromMap(Map<String, Object> values) {
        var extensions = new LinkedHashMap<>(values);
        FIELDS.forEach(extensions::remove);
        return new McpConnectionConfig(
                (String) values.get("type"),
                (String) values.get("command"),
                (List<String>) values.get("args"),
                (Map<String, String>) values.get("env"),
                (String) values.get("cwd"),
                (String) values.get("url"),
                (Map<String, String>) values.get("headers"),
                ((Number) values.get("timeoutMs")).longValue(),
                (String) values.get("protocolVersion"),
                extensions);
    }

    /** Retains the existing flat mcp.json and database representation. */
    @JsonValue
    public Map<String, Object> toMap() {
        var values = new LinkedHashMap<>(extensions);
        values.put("type", type);
        if ("stdio".equals(type)) {
            values.put("command", command);
            values.put("args", args);
            if (!env.isEmpty()) {
                values.put("env", env);
            }
            if (cwd != null) {
                values.put("cwd", cwd);
            }
        } else {
            values.put("url", url);
            if (!headers.isEmpty()) {
                values.put("headers", headers);
            }
        }
        values.put("timeoutMs", timeoutMs);
        if (protocolVersion != null) {
            values.put("protocolVersion", protocolVersion);
        }
        return Collections.unmodifiableMap(values);
    }

    public McpConnectionConfig withSecrets(Map<String, String> env, Map<String, String> headers) {
        return new McpConnectionConfig(
                type,
                command,
                args,
                env,
                cwd,
                url,
                headers,
                timeoutMs,
                protocolVersion,
                extensions);
    }
}
