package com.mindrails.backend.agent.api.websocket;

public final class AgentWebSocketProperties {
    public static int inboundBytes = 64 * 1024;
    public static int outboundBytes = 1024 * 1024;
    public static int batchBytes = 4 * 1024;
    public static int batchMillis = 30;
    public static int sendTimeoutMillis = 10_000;
    public static int pingMillis = 30_000;
    public static int pongTimeoutMillis = 60_000;
    public static int receiptLimit = 1024;

    private AgentWebSocketProperties() {}
}
