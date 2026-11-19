package com.mindrails.backend.tool.spec;

import java.io.InterruptedIOException;
import java.nio.file.Path;
import tools.jackson.databind.json.JsonMapper;

/** Stateless helpers shared by the built-in tools. */
final class ToolSupport {

    static final int MAX_OUTPUT_BYTES = 50 * 1024;

    static Path path(String value) {
        requireText(value, "path");
        return Path.of(value).toAbsolutePath().normalize();
    }

    static void requireText(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
    }

    static int positive(Integer value, int fallback, String name) {
        int result = value == null ? fallback : value;
        if (result <= 0) throw new IllegalArgumentException(name + " must be positive");
        return result;
    }

    static long offset(int page, int limit) {
        return ((long) page - 1) * limit;
    }

    static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Tool execution interrupted");
    }

    static String json(Object value) {
        return new JsonMapper().writeValueAsString(value);
    }
}
