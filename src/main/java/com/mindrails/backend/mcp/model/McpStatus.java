package com.mindrails.backend.mcp.model;

public record McpStatus(State state, String errorCode, String message) {
    public enum State {
        UNKNOWN,
        CONNECTING,
        READY,
        ERROR,
        DISABLED
    }

    public static McpStatus of(State state) {
        return new McpStatus(state, null, null);
    }
}
