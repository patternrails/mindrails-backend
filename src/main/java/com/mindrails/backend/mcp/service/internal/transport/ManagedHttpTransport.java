package com.mindrails.backend.mcp.service.internal.transport;

import com.mindrails.backend.mcp.model.McpConnectionConfig;

import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpHeaderEncoding;
import dev.langchain4j.mcp.client.transport.McpJson;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpInitializationNotification;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Streamable HTTP with bounded bodies, request-level cancellation and no implicit tools/call
 * replay.
 */
public final class ManagedHttpTransport implements McpTransport {
    private final URI url;
    private final Map<String, String> headers;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final Set<CompletableFuture<String>> pending = ConcurrentHashMap.newKeySet();
    private volatile String session;
    private volatile String protocolVersion;
    private volatile boolean modern;
    private volatile boolean closed;
    private McpOperationHandler handler;

    public ManagedHttpTransport(McpConnectionConfig config) {
        url = URI.create(config.url());
        headers = config.headers();
    }

    @Override
    public void start(McpOperationHandler handler) {
        this.handler = handler;
    }

    @Override
    public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest request) {
        return sendRequest(request)
                .thenApply(
                        response -> {
                            sendMessage(new McpInitializationNotification());
                            return response;
                        });
    }

    @Override
    public CompletableFuture<String> sendRequest(McpClientMessage message) {
        return sendRequest(new McpCallContext(null, message));
    }

    @Override
    public CompletableFuture<String> sendRequest(McpCallContext context) {
        if (closed) {
            return CompletableFuture.failedFuture(new IOException("MCP HTTP transport closed"));
        }
        var result = new CompletableFuture<String>();
        String body = McpJson.serialize(context.message());
        var builder =
                HttpRequest.newBuilder(url)
                        .header("Content-Type", "application/json")
                        .header("Accept", "application/json, text/event-stream");
        headers.forEach(builder::header);
        if (protocolVersion != null) {
            builder.header("MCP-Protocol-Version", protocolVersion);
        }
        if (!modern && session != null && !(context.message() instanceof McpInitializeRequest)) {
            builder.header("Mcp-Session-Id", session);
        }
        if (modern) {
            var fields = McpJson.toMap(body);
            builder.header("Mcp-Method", Objects.toString(fields.get("method"), ""));
            if (fields.get("params") instanceof Map<?, ?> params
                    && params.get("name") instanceof String name) {
                builder.header("Mcp-Name", McpHeaderEncoding.encode(name));
            }
            if (context.mcpParamHeaders() != null) {
                context.mcpParamHeaders()
                        .forEach((key, value) -> builder.header("Mcp-Param-" + key, value));
            }
        }
        if (context.message().getId() != null) {
            handler.expectResponse(context.message().getId(), result);
        }
        pending.add(result);
        final InputStream[] stream = {null};
        var request =
                http.sendAsync(
                        builder.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
                        HttpResponse.BodyHandlers.ofInputStream());
        result.whenComplete(
                (response, error) -> {
                    pending.remove(result);
                    if (error != null) {
                        request.cancel(true);
                    }
                    synchronized (stream) {
                        if (stream[0] != null) {
                            try {
                                stream[0].close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                });
        request.whenComplete(
                (response, error) -> {
                    if (error != null) {
                        result.completeExceptionally(new IOException("MCP HTTP request failed"));
                        return;
                    }
                    synchronized (stream) {
                        stream[0] = response.body();
                        if (result.isDone()) {
                            try {
                                stream[0].close();
                            } catch (IOException ignored) {
                            }
                        }
                    }
                    if (result.isDone()) {
                        return;
                    }
                    if (response.statusCode() < 200 || response.statusCode() >= 300) {
                        result.completeExceptionally(
                                new IOException("MCP HTTP status " + response.statusCode()));
                        return;
                    }
                    response.headers()
                            .firstValue("Mcp-Session-Id")
                            .ifPresent(value -> session = value);
                    if (context.message().getId() == null) {
                        result.complete(null);
                        return;
                    }
                    if (response.statusCode() == 202 || response.statusCode() == 204) {
                        result.completeExceptionally(
                                new IOException("MCP HTTP request returned no result"));
                        return;
                    }
                    Thread.startVirtualThread(() -> readResponse(response, result));
                });
        return result;
    }

    private void readResponse(
            HttpResponse<InputStream> response, CompletableFuture<String> result) {
        try (var input = response.body()) {
            String contentType = response.headers().firstValue("Content-Type").orElse("");
            if (contentType.contains("text/event-stream")) {
                readEvents(input, result);
            } else if (contentType.contains("application/json")) {
                byte[] bytes = input.readNBytes(1048577);
                if (bytes.length > 1048576) {
                    throw new IOException("MCP response exceeds 1 MiB");
                }
                handler.onMessage(new String(bytes, StandardCharsets.UTF_8));
                if (!result.isDone()) {
                    throw new IOException("MCP HTTP response did not match request");
                }
            } else {
                throw new IOException("Unsupported MCP HTTP response content type");
            }
        } catch (Exception error) {
            result.completeExceptionally(new IOException("MCP HTTP response failed"));
        }
    }

    private void readEvents(InputStream input, CompletableFuture<String> result)
            throws IOException {
        try (var reader =
                new BufferedReader(new InputStreamReader(input, StandardCharsets.UTF_8))) {
            var data = new StringBuilder();
            String line;
            while (!result.isDone() && (line = McpFraming.readLine(reader)) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        handler.onMessage(data.toString().stripTrailing());
                    }
                    data.setLength(0);
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).stripLeading()).append('\n');
                    if (data.length() > 1048576) {
                        throw new IOException("MCP SSE event exceeds 1 MiB");
                    }
                }
            }
            if (!result.isDone()) {
                throw new IOException("MCP response stream ended without a result");
            }
        }
    }

    @Override
    public void sendMessage(McpClientMessage message) {
        sendRequest(message);
    }

    @Override
    public void sendMessage(McpCallContext context) {
        sendRequest(context);
    }

    @Override
    public void setModernProtocol(boolean modern) {
        this.modern = modern;
    }

    @Override
    public void setProtocolVersion(String version) {
        protocolVersion = version;
    }

    @Override
    public boolean requiresCancellationNotification() {
        return false;
    }

    @Override
    public void checkHealth() {
        if (closed) {
            throw new IllegalStateException("MCP HTTP transport closed");
        }
    }

    @Override
    public void onFailure(Runnable callback) {}

    @Override
    public void close() {
        closed = true;
        pending.forEach(f -> f.cancel(true));
        http.shutdown();
    }
}
