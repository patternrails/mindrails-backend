package com.mindrails.backend.mcp.service.internal.transport;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.mcp.service.internal.McpToolException;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.metrics.MetricsRecords;
import com.mindrails.backend.metrics.MetricsSnapshot;

import dev.langchain4j.mcp.client.McpCallContext;
import dev.langchain4j.mcp.client.transport.McpJson;
import dev.langchain4j.mcp.client.transport.McpOperationHandler;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.protocol.McpCallToolParams;
import dev.langchain4j.mcp.protocol.McpCallToolRequest;
import dev.langchain4j.mcp.protocol.McpCancellationNotification;
import dev.langchain4j.mcp.protocol.McpClientMessage;
import dev.langchain4j.mcp.protocol.McpInitializeRequest;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Tracks actual wire requests so cancelling a run cancels only that run's operations. */
public final class ScopedMcpTransport implements McpTransport {
    private final McpTransport delegate;
    private final MetricsRecorder metricsRecorder;
    public static final String PROVIDER_CALL_ID = "mindrails.mcp.providerCallId";
    private final UUID serverId;
    private final long version;
    private final long timeout;
    private final int maxTools;
    private final long toolTimeout;
    private final Map<CompletableFuture<String>, Runnable> pending = new ConcurrentHashMap<>();
    private volatile boolean modern;
    private final Map<String, Map<String, Object>> rawTools = new ConcurrentHashMap<>();

    public ScopedMcpTransport(
            McpTransport delegate,
            long timeout,
            long toolTimeout,
            int maxTools,
            MetricsRecorder metricsRecorder,
            UUID serverId,
            long version) {
        this.serverId = serverId;
        this.version = version;
        this.metricsRecorder = metricsRecorder;
        this.delegate = delegate;
        this.timeout = timeout;
        this.toolTimeout = toolTimeout;
        this.maxTools = maxTools;
    }

    @Override
    public void start(McpOperationHandler handler) {
        delegate.start(handler);
    }

    @Override
    public CompletableFuture<String> sendInitializeRequest(McpInitializeRequest request) {
        return delegate.sendInitializeRequest(request);
    }

    @Override
    public CompletableFuture<String> sendRequest(McpClientMessage message) {
        return sendRequest(new McpCallContext(null, message));
    }

    @Override
    public CompletableFuture<String> sendRequest(McpCallContext context) {
        LlmClient.checkCancelled(context.invocationContext());
        Long operationId = context.message().getId();
        var cancellation = LlmClient.cancellation(context.invocationContext());
        boolean toolCall = context.message() instanceof McpCallToolRequest;
        MetricsSnapshot observation = null;
        if (context.message() instanceof McpCallToolRequest request) {
            String name = ((McpCallToolParams) request.getParams()).getName();
            String providerCallId =
                    context.invocationContext() == null
                                    || context.invocationContext().invocationParameters() == null
                            ? null
                            : context.invocationContext()
                                    .invocationParameters()
                                    .get(PROVIDER_CALL_ID);
            observation =
                    MetricsRecords.capability(
                            context.invocationContext(),
                            UUID.randomUUID(),
                            Instant.now(),
                            providerCallId,
                            name,
                            "MCP_TOOL",
                            serverId.toString(),
                            Long.toString(version),
                            null);
            metricsRecorder.record(observation);
        }
        var started = observation;
        var future = delegate.sendRequest(context);
        Runnable cancel =
                () -> {
                    future.cancel(true);
                    Thread.startVirtualThread(() -> notifyCancel(operationId));
                };
        if (cancellation != null) {
            cancellation.thenRun(
                    () -> {
                        if (!future.isDone()) {
                            cancel.run();
                        }
                    });
        }
        pending.put(future, cancel);
        future.orTimeout(toolCall ? toolTimeout : timeout, TimeUnit.MILLISECONDS);
        future.whenComplete(
                (response, error) -> {
                    pending.remove(future);
                    if (error instanceof TimeoutException) {
                        Thread.startVirtualThread(() -> notifyCancel(operationId));
                    }
                });
        return future.handle(
                (response, error) -> {
                    if (error != null) {
                        // LC4J converts a bare TimeoutException to successful text. Preserve it as
                        // an error.
                        if (error instanceof TimeoutException) {
                            throw new CompletionException(
                                    new IOException("MCP transport timed out", error));
                        }
                        throw new CompletionException(error);
                    }
                    if (cancellation != null && cancellation.isDone()) {
                        throw new CancellationException("Client invocation cancelled");
                    }
                    if (response.length() > 1048576) {
                        throw new IllegalStateException("MCP response exceeds 1 MiB");
                    }
                    var root = McpJson.toMap(response);
                    String failure = toolCall ? toolError(root) : null;
                    if (toolCall) {
                        metricsRecorder.record(
                                MetricsRecords.capabilityFinished(started, failure != null));
                    }
                    if (failure != null) {
                        throw new McpToolException(failure);
                    }
                    if (root.get("result") instanceof Map<?, ?> result
                            && result.get("tools") instanceof List<?> tools) {
                        for (Object tool : tools) {
                            if (!(tool instanceof Map<?, ?>)) {
                                throw new IllegalArgumentException("MCP tool must be an object");
                            }
                            @SuppressWarnings("unchecked")
                            var raw = (Map<String, Object>) tool;
                            rawTools.put((String) raw.get("name"), raw);
                            if (rawTools.size() > maxTools) {
                                throw new IllegalStateException("MCP tool limit exceeded");
                            }
                        }
                    }
                    return response;
                });
    }

    public void clearDiscoveredTools() {
        rawTools.clear();
    }

    public Map<String, Object> discoveredTool(String name) {
        return rawTools.get(name);
    }

    private static String toolError(Map<String, Object> response) {
        if (response.get("error") instanceof Map<?, ?> error) {
            return Objects.toString(error.get("message"), "MCP protocol error");
        }
        if (!(response.get("result") instanceof Map<?, ?> result)
                || !Boolean.TRUE.equals(result.get("isError"))) {
            return null;
        }
        if (result.get("structuredContent") != null) {
            return McpJson.serialize(result.get("structuredContent"));
        }
        if (result.get("content") instanceof List<?> content) {
            var text = new StringBuilder();
            for (Object block : content) {
                if (block instanceof Map<?, ?> item && "text".equals(item.get("type"))) {
                    text.append(item.get("text")).append('\n');
                }
            }
            return text.isEmpty() ? "MCP tool failed" : text.toString();
        }
        return "MCP tool failed";
    }

    private void notifyCancel(Long id) {
        if (id == null) {
            return;
        }
        try {
            if (!modern || delegate.requiresCancellationNotification()) {
                delegate.sendMessage(new McpCancellationNotification(id, "Cancelled"));
            }
        } catch (RuntimeException ignored) {
        }
    }

    @Override
    public void sendMessage(McpClientMessage message) {
        delegate.sendMessage(message);
    }

    @Override
    public void sendMessage(McpCallContext context) {
        delegate.sendMessage(context);
    }

    @Override
    public void checkHealth() {
        delegate.checkHealth();
    }

    @Override
    public void onFailure(Runnable action) {
        delegate.onFailure(action);
    }

    @Override
    public void setModernProtocol(boolean modern) {
        this.modern = modern;
        delegate.setModernProtocol(modern);
    }

    @Override
    public void setProtocolVersion(String version) {
        delegate.setProtocolVersion(version);
    }

    @Override
    public boolean requiresCancellationNotification() {
        return delegate.requiresCancellationNotification();
    }

    @Override
    public void close() throws IOException {
        delegate.close();
    }

    public void cancelPending() {
        pending.values().forEach(Runnable::run);
    }
}
