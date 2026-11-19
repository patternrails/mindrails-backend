package com.mindrails.backend.image;

import java.time.Instant;
import java.util.UUID;

/** Secrets never appear in the public configuration or a tool result. */
public final class ImageProvider {
    private ImageProvider() {}

    public record Input(String name, String providerName, String url, String apiKey, String model,
            Boolean defaultProvider, Boolean enabled, String size, Boolean promptExtend,
            Integer timeoutMillis) {
        @Override public String toString() { return "ImageProvider.Input[redacted]"; }
    }

    public record Info(UUID id, String name, String providerName, String url, String model,
            boolean hasApiKey, boolean defaultProvider, boolean enabled, String size,
            boolean promptExtend, int timeoutMillis, boolean tested, boolean testSuccess,
            Instant testedAt, Long testLatencyMs, String testMessage, long version) {
        public boolean available() { return enabled && hasApiKey && tested && testSuccess; }
    }

    public record Config(Info info, @com.fasterxml.jackson.annotation.JsonIgnore String apiKey) {
        @Override public String toString() { return "ImageProvider.Config[redacted]"; }
    }

    public record TestResult(UUID id, UUID providerId, long version, boolean connected,
            long latencyMs, String message, Instant testedAt) {}
}
