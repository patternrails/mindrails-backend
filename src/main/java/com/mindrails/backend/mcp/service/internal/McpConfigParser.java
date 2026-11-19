package com.mindrails.backend.mcp.service.internal;

import com.mindrails.backend.mcp.model.McpConnectionConfig;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Accepts client dialects at the import boundary and produces typed connection settings. */
public final class McpConfigParser {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> KNOWN =
            Set.of(
                    "type",
                    "command",
                    "args",
                    "env",
                    "cwd",
                    "url",
                    "headers",
                    "enabled",
                    "disabled",
                    "timeoutMs",
                    "protocolVersion");

    public record Parsed(
            String name, McpConnectionConfig config, boolean enabled, List<String> warnings) {}

    public List<Parsed> parse(Object value) {
        Object decoded;
        try {
            decoded =
                    value instanceof String text
                            ? JSON.readerFor(Object.class)
                                    .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                                    .with(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                                    .readValue(text)
                            : value;
        } catch (RuntimeException error) {
            throw new IllegalArgumentException("Invalid mcp.json JSON");
        }
        if (JSON.writeValueAsBytes(decoded).length > 1048576) {
            throw new IllegalArgumentException("mcp.json exceeds 1 MiB");
        }
        var root = object(decoded, "$");
        if (root.containsKey("mcpServers") == root.containsKey("servers")) {
            throw new IllegalArgumentException("Use exactly one of mcpServers or servers");
        }
        if (root.containsKey("inputs")) {
            throw new IllegalArgumentException(
                    "Interactive inputs are not supported; use backend environment references");
        }
        var servers =
                object(
                        root.get(root.containsKey("mcpServers") ? "mcpServers" : "servers"),
                        "mcpServers");
        if (servers.isEmpty() || servers.size() > 50) {
            throw new IllegalArgumentException("Supply 1..50 servers");
        }
        return servers.entrySet().stream()
                .map(entry -> normalize(entry.getKey(), entry.getValue()))
                .toList();
    }

    public Parsed normalize(String name, Object value) {
        if (name == null
                || name.isBlank()
                || name.length() > 128
                || name.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException(
                    "Server name must contain 1..128 printable characters");
        }
        var raw = object(value, name);
        boolean stdio = raw.containsKey("command");
        if (stdio == raw.containsKey("url")) {
            throw new IllegalArgumentException(name + ": supply either command or url");
        }
        String type =
                transport(raw.getOrDefault("type", stdio ? "stdio" : "streamable-http"), name);
        if (stdio != type.equals("stdio")) {
            throw new IllegalArgumentException(name + ": transport does not match command/url");
        }
        String command = null;
        String cwd = null;
        String url = null;
        List<String> args = List.of();
        if (stdio) {
            if (raw.containsKey("headers")) {
                throw new IllegalArgumentException(name + ": headers apply only to HTTP");
            }
            command = string(raw.get("command"), name + ".command");
            args = arguments(raw.getOrDefault("args", List.of()), name);
            cwd = raw.containsKey("cwd") ? string(raw.get("cwd"), name + ".cwd") : null;
        } else {
            if (raw.containsKey("env") || raw.containsKey("args") || raw.containsKey("cwd")) {
                throw new IllegalArgumentException(name + ": env/args/cwd apply only to stdio");
            }
            url = endpoint(raw.get("url"), name);
        }
        var env = stringValues(raw.getOrDefault("env", Map.of()), name + ".env", true);
        var headers = stringValues(raw.getOrDefault("headers", Map.of()), name + ".headers", false);
        String protocolVersion =
                raw.containsKey("protocolVersion")
                        ? string(raw.get("protocolVersion"), name + ".protocolVersion")
                        : null;
        var extensions = new LinkedHashMap<>(raw);
        KNOWN.forEach(extensions::remove);
        var warnings =
                extensions.keySet().stream()
                        .map(key -> "Preserved unsupported field: " + key)
                        .toList();
        var config =
                new McpConnectionConfig(
                        type,
                        command,
                        args,
                        env,
                        cwd,
                        url,
                        headers,
                        timeout(raw.getOrDefault("timeoutMs", 30000), name),
                        protocolVersion,
                        extensions);
        return new Parsed(name, config, enabled(raw, name), warnings);
    }

    private String transport(Object value, String name) {
        return switch (string(value, name + ".type").toLowerCase(Locale.ROOT)) {
            case "stdio" -> "stdio";
            case "http", "streamable_http", "streamable-http" -> "streamable-http";
            case "sse" -> "sse";
            default -> throw new IllegalArgumentException(name + ": unsupported transport");
        };
    }

    private List<String> arguments(Object value, String name) {
        if (!(value instanceof List<?> list)
                || list.size() > 100
                || list.stream().anyMatch(arg -> !(arg instanceof String))) {
            throw new IllegalArgumentException(
                    name + ".args must be a string array with at most 100 entries");
        }
        return list.stream().map(String.class::cast).toList();
    }

    private String endpoint(Object value, String name) {
        var uri = URI.create(string(value, name + ".url"));
        if (!Set.of("http", "https").contains(uri.getScheme())
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException(
                    name + ".url must be an HTTP(S) endpoint without credentials or fragment");
        }
        return uri.toString();
    }

    private Map<String, String> stringValues(Object value, String path, boolean environment) {
        var result = new LinkedHashMap<String, String>();
        for (var entry : object(value, path).entrySet()) {
            String key = entry.getKey();
            if (!(entry.getValue() instanceof String text) || key.isBlank()) {
                throw new IllegalArgumentException(path + " must map names to strings");
            }
            if (environment && !key.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new IllegalArgumentException(path + " contains an invalid variable name");
            }
            if (!environment
                    && (!key.matches("[!#$%&'*+.^_`|~0-9A-Za-z-]+")
                            || text.contains("\n")
                            || text.contains("\r"))) {
                throw new IllegalArgumentException(path + " contains an invalid header");
            }
            result.put(key, text);
        }
        return Map.copyOf(result);
    }

    private long timeout(Object value, String name) {
        if (!(value instanceof Number number)
                || number.doubleValue() != number.longValue()
                || number.longValue() < 1000
                || number.longValue() > 300000) {
            throw new IllegalArgumentException(
                    name + ".timeoutMs must be an integer between 1000 and 300000");
        }
        return number.longValue();
    }

    private boolean enabled(Map<String, Object> raw, String name) {
        if (raw.containsKey("disabled") && !(raw.get("disabled") instanceof Boolean)) {
            throw new IllegalArgumentException(name + ".disabled must be boolean");
        }
        Object enabled = raw.getOrDefault("enabled", !Boolean.TRUE.equals(raw.get("disabled")));
        if (raw.containsKey("enabled")
                && raw.containsKey("disabled")
                && Objects.equals(enabled, raw.get("disabled"))) {
            throw new IllegalArgumentException(name + ": enabled and disabled disagree");
        }
        if (!(enabled instanceof Boolean flag)) {
            throw new IllegalArgumentException(name + ".enabled must be boolean");
        }
        return flag;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> object(Object value, String path) {
        if (!(value instanceof Map<?, ?> map)
                || map.keySet().stream().anyMatch(key -> !(key instanceof String))) {
            throw new IllegalArgumentException(path + " must be an object");
        }
        return (Map<String, Object>) map;
    }

    private String string(Object value, String path) {
        if (!(value instanceof String text) || text.isBlank() || text.indexOf('\0') >= 0) {
            throw new IllegalArgumentException(path + " must be a non-empty string");
        }
        return text;
    }
}
