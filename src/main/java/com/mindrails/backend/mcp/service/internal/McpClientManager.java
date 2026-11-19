package com.mindrails.backend.mcp.service.internal;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.mcp.model.McpConnectionConfig;
import com.mindrails.backend.mcp.model.McpServer;
import com.mindrails.backend.mcp.model.McpStatus;
import com.mindrails.backend.mcp.model.McpStatus.State;
import com.mindrails.backend.mcp.model.McpTool;
import com.mindrails.backend.mcp.model.McpToolCatalog;
import com.mindrails.backend.mcp.registry.McpRegistry;
import com.mindrails.backend.mcp.service.McpService.AvailableTools;
import com.mindrails.backend.mcp.service.internal.transport.LegacySseTransport;
import com.mindrails.backend.mcp.service.internal.transport.ManagedHttpTransport;
import com.mindrails.backend.mcp.service.internal.transport.ManagedStdioTransport;
import com.mindrails.backend.mcp.service.internal.transport.ScopedMcpTransport;
import com.mindrails.backend.metrics.MetricsRecorder;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.service.tool.ToolExecutionResult;

import jakarta.annotation.PreDestroy;

import org.springframework.stereotype.Component;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/** Bounded, shared connections; no network operations occur inside registry transactions. */
@Component
public final class McpClientManager implements AutoCloseable {
    private static final class Connection implements AutoCloseable {
        final McpServer server;
        final McpConnectionConfig resolved;
        final ScopedMcpTransport transport;
        final DefaultMcpClient client;
        volatile List<ToolSpecification> tools = List.of();
        volatile long discoveredNanos;
        volatile long lastUsed = System.nanoTime();
        private boolean retired;
        private boolean disposed;
        private int active;

        private Connection(
                McpServer server,
                McpConnectionConfig resolved,
                ScopedMcpTransport transport,
                DefaultMcpClient client) {
            this.server = server;
            this.resolved = resolved;
            this.transport = transport;
            this.client = client;
        }

        synchronized void acquire() {
            if (retired) {
                throw new IllegalStateException("MCP configuration changed");
            }
            active++;
            lastUsed = System.nanoTime();
        }

        synchronized void release() {
            active--;
            lastUsed = System.nanoTime();
            if (retired && active == 0) {
                dispose();
            }
        }

        @Override
        public synchronized void close() {
            retired = true;
            if (active == 0) {
                dispose();
            }
        }

        private void dispose() {
            if (disposed) {
                return;
            }
            disposed = true;
            Thread.startVirtualThread(
                    () -> {
                        try {
                            client.close();
                        } catch (Exception ignored) {
                        }
                    });
        }

        String redact(String value) {
            String result = value == null ? "" : value;
            for (var values : List.of(resolved.env(), resolved.headers())) {
                for (String s : values.values()) {
                    if (!s.isEmpty()) {
                        result = result.replace(s, "[redacted]");
                    }
                    if (s.startsWith("Bearer ") && s.length() > 7) {
                        result = result.replace(s.substring(7), "[redacted]");
                    }
                }
            }
            return result;
        }
    }

    private record Slot(long version, long createdNanos, CompletableFuture<Connection> future) {}

    private final JsonMapper json = JsonMapper.builder().build();
    private final McpRegistry registry;
    private final McpSecrets secrets;
    private final McpProperties properties;
    private final ConcurrentHashMap<UUID, Slot> clients = new ConcurrentHashMap<>();

    private record RuntimeKey(UUID id, long version) {}

    private final ConcurrentHashMap<RuntimeKey, McpStatus> states = new ConcurrentHashMap<>();
    private final ExecutorService preparation =
            new ThreadPoolExecutor(
                    8,
                    8,
                    30,
                    TimeUnit.SECONDS,
                    new ArrayBlockingQueue<>(50),
                    Thread.ofPlatform().daemon().name("mcp-prepare-", 0).factory(),
                    new ThreadPoolExecutor.AbortPolicy());
    private final ScheduledExecutorService maintenance =
            Executors.newSingleThreadScheduledExecutor(
                    Thread.ofPlatform().daemon().name("mcp-maintenance").factory());
    private final AtomicBoolean closed = new AtomicBoolean();
    private final MetricsRecorder metricsRecorder;

    public McpClientManager(
            McpRegistry registry,
            McpSecrets secrets,
            McpProperties properties,
            MetricsRecorder metricsRecorder) {
        this.metricsRecorder = metricsRecorder;
        this.registry = registry;
        this.secrets = secrets;
        this.properties = properties;
        maintenance.scheduleWithFixedDelay(this::maintain, 30, 30, TimeUnit.SECONDS);
    }

    public McpStatus status(McpServer server) {
        return !server.enabled()
                ? new McpStatus(State.DISABLED, null, null)
                : states.getOrDefault(
                        new RuntimeKey(server.id(), server.version()),
                        new McpStatus(State.UNKNOWN, null, null));
    }

    /** Uses the same monitor as service writes, without waiting for network I/O under it. */
    private synchronized CompletableFuture<Connection> connect(McpServer server) {
        if (closed.get()) {
            return CompletableFuture.failedFuture(new IllegalStateException("MCP runtime closed"));
        }
        if (!current(server.id(), server.version())) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("MCP configuration changed"));
        }
        var old = clients.get(server.id());
        boolean retryDelay =
                old != null && System.nanoTime() - old.createdNanos() < TimeUnit.SECONDS.toNanos(5);
        if (old != null
                && old.version() == server.version()
                && (!old.future().isCompletedExceptionally() || retryDelay)) {
            return old.future();
        }
        if (old != null) {
            old.future().thenAccept(Connection::close);
        }
        var slot = new Slot(server.version(), System.nanoTime(), new CompletableFuture<>());
        clients.put(server.id(), slot);
        states.put(new RuntimeKey(server.id(), server.version()), McpStatus.of(State.CONNECTING));
        try {
            preparation.execute(() -> prepare(server, slot));
        } catch (RejectedExecutionException error) {
            slot.future().completeExceptionally(error);
            states.put(
                    new RuntimeKey(server.id(), server.version()),
                    new McpStatus(State.ERROR, "MCP_BUSY", "MCP preparation capacity exceeded"));
        }
        return slot.future();
    }

    private void prepare(McpServer server, Slot slot) {
        Connection connection = null;
        try {
            connection = create(server);
            refresh(connection);
            synchronized (this) {
                if (closed.get() || clients.get(server.id()) != slot) {
                    throw new IllegalStateException("MCP connection retired during preparation");
                }
                states.put(
                        new RuntimeKey(server.id(), server.version()), McpStatus.of(State.READY));
                slot.future().complete(connection);
            }
        } catch (Exception error) {
            if (connection != null) {
                connection.close();
            }
            if (clients.get(server.id()) == slot) {
                states.put(
                        new RuntimeKey(server.id(), server.version()),
                        new McpStatus(
                                State.ERROR, "MCP_CONNECTION_FAILED", safeConnectionError(error)));
            }
            slot.future()
                    .completeExceptionally(
                            new IllegalStateException("MCP connection or discovery failed"));
        }
    }

    private Connection create(McpServer server) {
        var config = secrets.resolve(server.config());
        McpTransport raw =
                switch (server.config().type()) {
                    case "stdio" -> {
                        if (!properties.isStdioEnabled()) {
                            throw new IllegalStateException("MCP stdio disabled by deployment");
                        }
                        yield new ManagedStdioTransport(config);
                    }
                    case "sse" -> new LegacySseTransport(config);
                    default -> new ManagedHttpTransport(config);
                };
        var transport =
                new ScopedMcpTransport(
                        raw,
                        properties.getDiscoveryTimeoutMs(),
                        server.config().timeoutMs(),
                        properties.getMaxTools(),
                        metricsRecorder,
                        server.id(),
                        server.version());
        try {
            var builder =
                    DefaultMcpClient.builder()
                            .key(server.id().toString())
                            .clientName("MindRails")
                            .transport(transport)
                            .initializationTimeout(Duration.ofSeconds(10))
                            .protocolDetectionTimeout(Duration.ofSeconds(2))
                            .toolExecutionTimeout(
                                    Duration.ofMillis(server.config().timeoutMs() + 1000))
                            .cacheToolList(false)
                            .autoHealthCheck(false)
                            .logHandler(message -> {})
                            .toolResultConverter(this::convertResult);
            if (config.protocolVersion() != null) {
                builder.protocolVersion(config.protocolVersion());
            }
            if (server.config().type().equals("sse")) {
                builder.protocolVersion("2025-11-25");
            }
            return new Connection(server, config, transport, builder.build());
        } catch (RuntimeException error) {
            try {
                transport.close();
            } catch (Exception ignored) {
            }
            throw error;
        }
    }

    private ToolExecutionResult convertResult(List<Map<String, Object>> content, boolean isError) {
        var text = new StringBuilder();
        for (var block : content) {
            if ("text".equals(block.get("type"))) {
                text.append(block.getOrDefault("text", "")).append('\n');
            } else {
                text.append("[MCP content: ")
                        .append(block.get("type"))
                        .append("; binary/embedded content omitted]\n");
            }
            if (text.length() > properties.getMaxResultChars()) {
                break;
            }
        }
        return ToolExecutionResult.builder()
                .resultText(limit(text.toString()))
                .isError(isError)
                .build();
    }

    private void refresh(Connection entry) {
        synchronized (entry) {
            entry.transport.clearDiscoveredTools();
            entry.client.evictToolListCache();
            var tools = entry.client.listTools();
            if (tools.size() > properties.getMaxTools()) {
                throw new IllegalStateException("MCP tool limit exceeded");
            }
            entry.tools = List.copyOf(tools);
            entry.discoveredNanos = System.nanoTime();
            var views = new ArrayList<Map<String, Object>>();
            for (var tool : tools) {
                var raw = entry.transport.discoveredTool(tool.name());
                var view =
                        new LinkedHashMap<String, Object>(
                                raw == null ? Map.of("name", tool.name()) : raw);
                view.put("modelName", McpTool.modelName(entry.server.id(), tool.name()));
                views.add(
                        json.readValue(
                                entry.redact(json.writeValueAsString(view)),
                                new TypeReference<Map<String, Object>>() {}));
            }
            registry.saveCatalog(
                    entry.server.id(),
                    new McpToolCatalog(entry.server.version(), Instant.now(), List.copyOf(views)));
        }
    }

    public McpStatus test(McpServer server, boolean forceRefresh) {
        try {
            clients.computeIfPresent(
                    server.id(),
                    (id, slot) -> slot.future().isCompletedExceptionally() ? null : slot);
            var entry =
                    connect(server)
                            .get(properties.getPreparationTimeoutMs(), TimeUnit.MILLISECONDS);
            entry.lastUsed = System.nanoTime();
            if (forceRefresh) {
                Future<?> task = preparation.submit(() -> refresh(entry));
                task.get(properties.getDiscoveryTimeoutMs() + 1000, TimeUnit.MILLISECONDS);
            }
            var state = new McpStatus(State.READY, null, null);
            states.put(new RuntimeKey(server.id(), server.version()), state);
            return state;
        } catch (Exception error) {
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            var state =
                    new McpStatus(State.ERROR, "MCP_CONNECTION_FAILED", safeConnectionError(error));
            states.put(new RuntimeKey(server.id(), server.version()), state);
            return state;
        }
    }

    public ToolExecutionResult execute(
            UUID serverId, long version, ToolExecutionRequest request, InvocationContext context)
            throws Exception {
        LlmClient.checkCancelled(context);
        var current = registry.find(serverId).orElse(null);
        if (current == null || !current.enabled() || current.version() != version) {
            return ToolExecutionResult.builder()
                    .isError(true)
                    .resultText("MCP_CONFIG_CHANGED: refresh tools on the next turn")
                    .build();
        }
        var entry =
                connect(current).get(properties.getPreparationTimeoutMs(), TimeUnit.MILLISECONDS);
        return execute(entry, request, context);
    }

    private ToolExecutionResult execute(
            Connection entry, ToolExecutionRequest request, InvocationContext context)
            throws Exception {
        LlmClient.checkCancelled(context);
        CompletableFuture<ToolExecutionResult> future;
        var parameters =
                new dev.langchain4j.invocation.InvocationParameters(
                        context.invocationParameters() == null
                                ? Map.of()
                                : context.invocationParameters().asMap());
        if (request.id() != null) {
            parameters.put(ScopedMcpTransport.PROVIDER_CALL_ID, request.id());
        }
        var callContext = context.toBuilder().invocationParameters(parameters).build();
        synchronized (this) {
            var current = registry.find(entry.server.id()).orElse(null);
            if (current == null
                    || !current.enabled()
                    || current.version() != entry.server.version()) {
                return ToolExecutionResult.builder()
                        .isError(true)
                        .resultText("MCP_CONFIG_CHANGED: refresh tools on the next turn")
                        .build();
            }
            entry.acquire();
            try {
                future = entry.client.executeToolAsync(request, callContext);
            } catch (RuntimeException error) {
                entry.release();
                throw error;
            }
        }
        try {
            var result = future.get(entry.server.config().timeoutMs(), TimeUnit.MILLISECONDS);
            LlmClient.checkCancelled(context);
            return ToolExecutionResult.builder()
                    .resultText(limit(entry.redact(com.mindrails.backend.tool.ToolResultText.from(result.resultContents()))))
                    .attributes(result.attributes())
                    .isError(result.isError())
                    .build();
        } catch (Exception error) {
            if (McpToolException.isDefinite(error)) {
                throw new McpToolException(
                        entry.redact(
                                Objects.toString(
                                        McpToolException.rootMessage(error), "MCP tool failed")));
            }
            if (!McpToolException.hasCause(error, TimeoutException.class)
                    && !McpToolException.hasCause(error, CancellationException.class)) {
                discard(
                        entry,
                        "MCP_CONNECTION_FAILED",
                        "MCP connection failed; reconnect on next turn");
            }
            throw error;
        } finally {
            entry.release();
        }
    }

    private String limit(String text) {
        return text.length() <= properties.getMaxResultChars()
                ? text
                : text.substring(0, properties.getMaxResultChars()) + "\n[MCP result truncated]";
    }

    public AvailableTools availableTools(InvocationContext context) {
        return availableTools(context, null);
    }
    public AvailableTools availableTools(InvocationContext context, java.util.Set<UUID> allowed) {
        var entries = available(context, allowed);
        var tools = new ArrayList<McpTool>();
        for (var entry : entries) {
            for (var specification : entry.tools) {
                tools.add(
                        new McpTool(
                                entry.server.id(),
                                entry.server.name(),
                                entry.server.version(),
                                specification));
            }
        }
        return new AvailableTools(List.copyOf(tools), unavailableSummary(entries, allowed));
    }

    private List<Connection> available(InvocationContext context, java.util.Set<UUID> allowed) {
        LlmClient.checkCancelled(context);
        var servers = registry.list().stream().filter(s -> allowed==null || allowed.contains(s.id())).filter(McpServer::enabled).toList();
        var futures = servers.stream().map(this::connect).toList();
        var result = new ArrayList<Connection>();
        long deadline =
                System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(properties.getPreparationTimeoutMs());
        for (var future : futures) {
            LlmClient.checkCancelled(context);
            try {
                var entry =
                        future.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
                entry.lastUsed = System.nanoTime();
                result.add(entry);
                if (System.nanoTime() - entry.discoveredNanos
                        > TimeUnit.MILLISECONDS.toNanos(properties.getCatalogTtlMs())) {
                    preparation.execute(() -> refreshExpired(entry));
                }
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
                throw new CancellationException("MCP preparation interrupted");
            } catch (Exception error) {
                /* A failed server does not hide the healthy ones. */
            }
        }
        return List.copyOf(result);
    }

    private void refreshExpired(Connection entry) {
        try {
            refresh(entry);
        } catch (RuntimeException error) {
            discard(entry, "MCP_DISCOVERY_FAILED", "MCP tool refresh failed");
        }
    }

    private synchronized void discard(Connection entry, String code, String message) {
        var slot = clients.get(entry.server.id());
        if (slot != null
                && !slot.future().isCompletedExceptionally()
                && slot.future().getNow(null) == entry
                && clients.remove(entry.server.id(), slot)) {
            entry.close();
            states.put(
                    new RuntimeKey(entry.server.id(), entry.server.version()),
                    new McpStatus(State.ERROR, code, message));
        }
    }

    private boolean current(UUID id, long version) {
        return registry.find(id)
                .filter(server -> server.enabled() && server.version() == version)
                .isPresent();
    }

    private String unavailableSummary(List<Connection> ready, java.util.Set<UUID> allowed) {
        var ids =
                ready.stream()
                        .map(entry -> entry.server.id())
                        .collect(java.util.stream.Collectors.toSet());
        String text =
                registry.list().stream()
                        .filter(s -> allowed==null || allowed.contains(s.id()))
                        .filter(McpServer::enabled)
                        .filter(server -> !ids.contains(server.id()))
                        .map(server -> server.name() + ": " + status(server).state())
                        .collect(java.util.stream.Collectors.joining("; "));
        return text.substring(0, Math.min(2000, text.length()));
    }

    public synchronized void reconcile() {
        try (var ignored = com.mindrails.backend.identity.ResourceAccess.system()) {
        var definitions = registry.list();
        clients.forEach(
                (id, slot) -> {
                    var current =
                            definitions.stream()
                                    .filter(s -> s.id().equals(id))
                                    .findFirst()
                                    .orElse(null);
                    if (current == null
                            || !current.enabled()
                            || current.version() != slot.version()) {
                        if (current == null || !current.enabled()) {
                            slot.future().thenAccept(entry -> entry.transport.cancelPending());
                        }
                        if (clients.remove(id, slot)) {
                            slot.future().thenAccept(Connection::close);
                        }
                        states.remove(new RuntimeKey(id, slot.version()));
                    }
                });
    }

    }

    private void maintain() {
        try {
            reconcile();
            clients.forEach(
                    (id, slot) -> {
                        if (slot.future().isCompletedExceptionally()) {
                            return;
                        }
                        var entry = slot.future().getNow(null);
                        if (entry != null
                                && System.nanoTime() - entry.lastUsed
                                        > TimeUnit.MILLISECONDS.toNanos(
                                                properties.getIdleTimeoutMs())
                                && clients.remove(id, slot)) {
                            entry.close();
                            states.remove(new RuntimeKey(id, slot.version()));
                        }
                    });
        } catch (RuntimeException ignored) {
        }
    }

    private static String safeConnectionError(Exception error) {
        String message = error.getMessage();
        if (message != null
                && (message.startsWith("Missing environment variable:")
                        || message.equals("MCP stdio disabled by deployment"))) {
            return message;
        }
        return "MCP connection or discovery failed; check endpoint, process, authentication and"
                + " timeouts";
    }

    @PreDestroy
    @Override
    public synchronized void close() {
        closed.set(true);
        maintenance.shutdownNow();
        preparation.shutdownNow();
        for (var slot : clients.values()) {
            slot.future().thenAccept(Connection::close);
            slot.future().completeExceptionally(new IllegalStateException("MCP runtime closed"));
        }
        clients.clear();
        states.clear();
    }
}
