package com.mindrails.backend.mcp.service.internal.transport;

import com.mindrails.backend.mcp.model.McpConnectionConfig;

import dev.langchain4j.mcp.client.McpCallContext;
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
import java.util.concurrent.CompletableFuture;

/** Legacy HTTP+SSE adapter. Endpoint announcements must remain on the configured origin. */
public final class LegacySseTransport implements McpTransport {
    private final URI url;
    private final Map<String, String> headers;
    private final HttpClient http =
            HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NEVER)
                    .build();
    private final CompletableFuture<URI> endpoint = new CompletableFuture<>();
    private volatile InputStream stream;
    private volatile boolean closed;
    private McpOperationHandler handler;

    public LegacySseTransport(McpConnectionConfig config) {
        url = URI.create(config.url());
        headers = config.headers();
    }

    @Override
    public void start(McpOperationHandler handler) {
        this.handler = handler;
        http.sendAsync(
                        request(url).header("Accept", "text/event-stream").GET().build(),
                        HttpResponse.BodyHandlers.ofInputStream())
                .whenComplete(
                        (response, error) -> {
                            if (error != null) {
                                endpoint.completeExceptionally(
                                        new IOException("MCP SSE connection failed"));
                                return;
                            }
                            stream = response.body();
                            if (response.statusCode() != 200 || closed) {
                                close();
                                endpoint.completeExceptionally(
                                        new IOException("MCP SSE connection rejected"));
                                return;
                            }
                            Thread.startVirtualThread(this::readEvents);
                        });
    }

    private HttpRequest.Builder request(URI uri) {
        var builder = HttpRequest.newBuilder(uri);
        headers.forEach(builder::header);
        return builder;
    }

    private void readEvents() {
        try (var reader =
                new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String event = "message", line;
            var data = new StringBuilder();
            while (!closed && (line = McpFraming.readLine(reader)) != null) {
                if (line.isEmpty()) {
                    if (!data.isEmpty()) {
                        String value = data.toString().stripTrailing();
                        if (event.equals("endpoint")) {
                            URI target = url.resolve(value);
                            if (!Objects.equals(url.getScheme(), target.getScheme())
                                    || !Objects.equals(url.getHost(), target.getHost())
                                    || url.getPort() != target.getPort()
                                    || target.getUserInfo() != null) {
                                throw new IOException("MCP SSE endpoint changed origin");
                            }
                            endpoint.complete(target);
                        } else if (event.equals("message")) {
                            handler.onMessage(value);
                        }
                    }
                    event = "message";
                    data.setLength(0);
                } else if (line.startsWith("event:")) {
                    event = line.substring(6).trim();
                } else if (line.startsWith("data:")) {
                    data.append(line.substring(5).stripLeading()).append('\n');
                    if (data.length() > 1048576) {
                        throw new IOException("MCP SSE event too large");
                    }
                }
            }
        } catch (Exception error) {
            endpoint.completeExceptionally(new IOException("MCP SSE stream failed"));
        } finally {
            handler.cancelAllPendingOperations("MCP SSE stream closed");
        }
    }

    @Override
    public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest request) {
        return sendRequest(request)
                .thenApply(
                        result -> {
                            sendMessage(new McpInitializationNotification());
                            return result;
                        });
    }

    @Override
    public CompletableFuture<String> sendRequest(McpClientMessage message) {
        var future = new CompletableFuture<String>();
        handler.expectResponse(message.getId(), future);
        post(message)
                .whenComplete(
                        (v, error) -> {
                            if (error != null) {
                                future.completeExceptionally(error);
                            }
                        });
        return future;
    }

    @Override
    public CompletableFuture<String> sendRequest(McpCallContext context) {
        return sendRequest(context.message());
    }

    private CompletableFuture<Void> post(McpClientMessage message) {
        return endpoint.thenCompose(
                        uri ->
                                http.sendAsync(
                                        request(uri)
                                                .timeout(Duration.ofSeconds(10))
                                                .header("Content-Type", "application/json")
                                                .POST(
                                                        HttpRequest.BodyPublishers.ofString(
                                                                McpJson.serialize(message)))
                                                .build(),
                                        HttpResponse.BodyHandlers.discarding()))
                .thenApply(
                        response -> {
                            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                                throw new IllegalStateException("MCP SSE message rejected");
                            }
                            return null;
                        });
    }

    @Override
    public void sendMessage(McpClientMessage message) {
        post(message);
    }

    @Override
    public void sendMessage(McpCallContext context) {
        sendMessage(context.message());
    }

    @Override
    public void checkHealth() {
        if (closed) {
            throw new IllegalStateException("MCP SSE closed");
        }
    }

    @Override
    public void onFailure(Runnable action) {}

    @Override
    public void close() {
        closed = true;
        if (handler != null) {
            handler.cancelAllPendingOperations("MCP SSE closed");
        }
        endpoint.completeExceptionally(new IOException("MCP SSE closed"));
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
        http.shutdown();
    }
}
