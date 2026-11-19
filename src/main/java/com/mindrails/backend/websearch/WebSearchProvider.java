package com.mindrails.backend.websearch;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/** Public catalog entry. The credential is stored separately from the fields sent to clients. */
public record WebSearchProvider(
        UUID id,
        String name,
        String type,
        String endpoint,
        String modelName,
        Map<String, Object> options,
        int timeoutMillis,
        boolean active,
        long version,
        Instant createdAt,
        Instant updatedAt) {

    static final String OPENAI = "openai_responses";
    static final String DEEPSEEK = "deepseek_search";
    static final String BOCHA = "bocha_web_search";

    public record TestResult(UUID providerId, long version, boolean connected, long latencyMs, String message) {}

    public record Input(String name, String type, String endpoint, String apiKey, String modelName,
            Map<String, Object> options, Integer timeoutMillis, Long version) {
        @Override public String toString() {
            return "WebSearchProvider.Input[redacted]";
        }
    }
}
