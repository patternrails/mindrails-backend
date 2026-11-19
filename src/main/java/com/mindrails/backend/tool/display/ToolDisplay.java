package com.mindrails.backend.tool.display;

import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Lossy UI projection only. Never pass these values to tool executors or model memory. */
public final class ToolDisplay {
    public static final int LIMIT = 200;
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final Set<String> PATH_KEYS = Set.of("path", "filePath", "file_path", "filename");
    private static final Set<String> PATH_ONLY = Set.of("write", "write_file", "edit", "edit_file");
    private static final Set<String> KEYS = Set.of("path", "filePath", "file_path", "filename", "command", "query", "pattern", "glob", "offset", "limit", "startLine", "endLine", "url", "name", "skillName", "skill_name", "timeout");
    private ToolDisplay() {}

    public static String text(String value) {
        if (value == null || value.codePointCount(0, value.length()) <= LIMIT) return value;
        return value.substring(0, value.offsetByCodePoints(0, LIMIT)) + "…";
    }

    public static Map<String, Object> object(Object value) {
        if (value instanceof String s) {
            try {
                var parsed = JSON.readValue(s, new TypeReference<LinkedHashMap<String,Object>>() {});
                return parsed == null ? Map.of() : parsed;
            }
            catch (RuntimeException ignored) { return Map.of("preview", text(s)); }
        }
        if (value instanceof Map<?,?> m) {
            var copy = new LinkedHashMap<String,Object>();
            m.forEach((k,v) -> copy.put(String.valueOf(k), v));
            return copy;
        }
        return Map.of();
    }

    public static Map<String, Object> arguments(String name, Object value) {
        var original = object(value);
        var selected = new LinkedHashMap<String,Object>();
        boolean pathOnly = PATH_ONLY.contains(name);
        original.forEach((k,v) -> {
            if (pathOnly ? PATH_KEYS.contains(k) : KEYS.contains(k)) selected.put(k,v);
        });
        if (selected.isEmpty() && !pathOnly) selected.putAll(original);
        String json = JSON.writeValueAsString(selected);
        return json.codePointCount(0, json.length()) <= LIMIT ? selected : Map.of("preview", text(json));
    }

    public static String result(String name, String value, boolean failed) {
        if (!failed && PATH_ONLY.contains(name)) return null;
        if (value == null) return null;
        if (Set.of("bash", "powershell").contains(name)) {
            var fields = object(value);
            if (fields.containsKey("output")) {
                String status = "exitCode=" + fields.get("exitCode") + (Boolean.TRUE.equals(fields.get("timedOut")) ? " · timed out" : "");
                return text(status + "\n" + Objects.toString(fields.get("output"), ""));
            }
        }
        return text(value);
    }

    /** Preserve native message shape for archived UI clients, without exposing full tool bodies. */
    public static String messages(String json) {
        if (json == null) return "[]";
        var messages = JSON.readValue(json, new TypeReference<List<LinkedHashMap<String,Object>>>() {});
        for (var message : messages) {
            if (message.get("toolExecutionRequests") instanceof List<?> calls) {
                var projected = new ArrayList<Map<String,Object>>();
                for (var call : calls) {
                    var copy = object(call);
                    copy.put("arguments", JSON.writeValueAsString(arguments(Objects.toString(copy.get("name"), ""), copy.get("arguments"))));
                    projected.add(copy);
                }
                message.put("toolExecutionRequests", projected);
            }
            if ("TOOL_EXECUTION_RESULT".equals(message.get("type"))) {
                String body = Objects.toString(message.get("text"), "");
                if (message.get("contents") instanceof List<?> contents) {
                    body = contents.stream().map(ToolDisplay::object).map(c -> Objects.toString(c.get("text"), "")).reduce((a,b) -> a + "\n" + b).orElse(body);
                }
                String preview = result(Objects.toString(message.get("toolName"), ""), body, Boolean.TRUE.equals(message.get("isError")));
                message.put("text", preview == null ? "" : preview);
                message.put("contents", preview == null ? List.of() : List.of(Map.of("type", "TEXT", "text", preview)));
                message.remove("attributes");
            }
        }
        return JSON.writeValueAsString(messages);
    }
}
