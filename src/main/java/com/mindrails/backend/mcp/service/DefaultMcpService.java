package com.mindrails.backend.mcp.service;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.history.service.internal.HistoryRecords;
import com.mindrails.backend.mcp.model.McpServer;
import com.mindrails.backend.mcp.model.McpServerDetails;
import com.mindrails.backend.mcp.model.McpStatus;
import com.mindrails.backend.mcp.model.McpStatus.State;
import com.mindrails.backend.mcp.model.McpTool;
import com.mindrails.backend.mcp.model.McpToolCatalog;
import com.mindrails.backend.mcp.registry.McpRegistry;
import com.mindrails.backend.mcp.service.internal.McpClientManager;
import com.mindrails.backend.mcp.service.internal.McpConfigParser;
import com.mindrails.backend.mcp.service.internal.McpProperties;
import com.mindrails.backend.mcp.service.internal.McpSecrets;
import com.mindrails.backend.mcp.service.internal.McpToolException;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.exception.ToolArgumentsException;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.ToolExecutionResult;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.TransactionException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;
import java.util.function.Supplier;

@Service
public class DefaultMcpService implements McpService {
    private final McpToolAuthorization authorization;
    private final McpRegistry registry;
    private final McpClientManager clients;
    private final McpSecrets secrets;
    private final McpProperties properties;
    private final ChatHistory chatHistory;
    private final McpConfigParser parser = new McpConfigParser();

    public DefaultMcpService(
            McpRegistry registry,
            McpClientManager clients,
            McpSecrets secrets,
            McpProperties properties,
            ChatHistory chatHistory,
            McpToolAuthorization authorization) {
        this.authorization = authorization;
        this.registry = registry;
        this.clients = clients;
        this.secrets = secrets;
        this.properties = properties;
        this.chatHistory = chatHistory;
    }

    @Override
    public ServiceResult<Page> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            return ServiceResult.failure(
                    ErrorCode.INVALID_ARGUMENT,
                    "page must be non-negative and size must be between 1 and 100");
        }
        return storage(
                () -> {
                    var all = registry.list();
                    long offset = (long) page * size;
                    return ServiceResult.success(
                            new Page(
                                    all.stream()
                                            .skip(offset)
                                            .limit(size)
                                            .map(this::details)
                                            .toList(),
                                    all.size(),
                                    page,
                                    size,
                                    offset + size < all.size()));
                });
    }

    @Override
    public ServiceResult<McpServerDetails> get(UUID id) {
        return storage(() -> find(id).map(this::details));
    }

    @Override
    public ServiceResult<Imported> importConfig(
            Object config, String mode, Map<String, Long> expectedVersions) {
        return storage(() -> importServers(config, mode, expectedVersions));
    }

    private ServiceResult<Imported> importServers(
            Object config, String mode, Map<String, Long> expectedVersions) {
        if (mode != null && !mode.equals("CREATE_ONLY") && !mode.equals("UPSERT")) {
            return ServiceResult.failure(
                    ErrorCode.INVALID_ARGUMENT, "mode must be CREATE_ONLY or UPSERT");
        }
        var parsed = parser.parse(config);
        // The runtime monitor coordinates committed changes with the start of a tool call.
        synchronized (clients) {
            var previous = new HashMap<String, McpServer>();
            registry.list().forEach(server -> previous.put(server.name(), server));
            var changed = new ArrayList<McpServer>();
            var expected = new HashMap<UUID, Long>();
            for (var item : parsed) {
                var old = previous.get(item.name());
                if (old != null) {
                    if (!"UPSERT".equals(mode)) {
                        return ServiceResult.failure(
                                ErrorCode.CONFLICT, "Server already exists: " + item.name());
                    }
                    Long version =
                            expectedVersions == null ? null : expectedVersions.get(item.name());
                    if (version == null || version != old.version()) {
                        return ServiceResult.failure(
                                ErrorCode.CONFLICT,
                                "Supply current expectedVersions for " + item.name());
                    }
                    expected.put(old.id(), version);
                }
                changed.add(create(item, old));
            }
            var saved =
                    registry.saveAll(changed, expected, properties.getMaxServers())
                            .onSuccess(clients::reconcile);
            return saved.map(
                    ignored ->
                            new Imported(
                                    changed.stream().map(this::details).toList(),
                                    parsed.stream()
                                            .flatMap(item -> item.warnings().stream())
                                            .toList()));
        }
    }

    @Override
    public ServiceResult<McpServerDetails> update(
            UUID id, long version, Object config, Boolean enabled) {
        if (id == null || version < 0) {
            return ServiceResult.failure(
                    ErrorCode.INVALID_ARGUMENT, "ID and a non-negative version are required");
        }
        return storage(() -> updateServer(id, version, config, enabled));
    }

    private ServiceResult<McpServerDetails> updateServer(
            UUID id, long version, Object config, Boolean enabled) {
        synchronized (clients) {
            var old = registry.find(id).orElse(null);
            if (old == null) {
                return ServiceResult.failure(ErrorCode.NOT_FOUND, "MCP server not found");
            }
            if (old.version() != version) {
                return ServiceResult.failure(
                        ErrorCode.CONFLICT, "MCP server was edited; refresh before retrying");
            }
            var raw =
                    new HashMap<>(
                            config == null
                                    ? secrets.masked(old.config()).toMap()
                                    : McpConfigParser.object(config, "config"));
            raw.put("enabled", enabled == null ? old.enabled() : enabled);
            var server = create(parser.normalize(old.name(), raw), old);
            return registry.saveAll(
                            List.of(server), Map.of(id, version), properties.getMaxServers())
                    .onSuccess(clients::reconcile)
                    .map(ignored -> details(server));
        }
    }

    @Override
    public ServiceResult<Void> delete(UUID id, long version) {
        if (id == null || version < 0) {
            return ServiceResult.failure(
                    ErrorCode.INVALID_ARGUMENT, "ID and a non-negative version are required");
        }
        return storage(
                () -> {
                    synchronized (clients) {
                        return registry.remove(id, version).onSuccess(clients::reconcile);
                    }
                });
    }

    @Override
    public ServiceResult<McpStatus> test(UUID id) {
        return test(id, false);
    }

    @Override
    public ServiceResult<McpStatus> refreshTools(UUID id) {
        return test(id, true);
    }

    private ServiceResult<McpStatus> test(UUID id, boolean refresh) {
        return storage(
                () ->
                        find(id).flatMap(
                                        server -> {
                                            if (!server.enabled()) {
                                                return ServiceResult.failure(
                                                        ErrorCode.CONFLICT,
                                                        "Enable the server before testing or"
                                                            + " refreshing tools");
                                            }
                                            return ServiceResult.success(
                                                    clients.test(server, refresh));
                                        }));
    }

    @Override
    public ServiceResult<Tools> tools(UUID id) {
        return storage(() -> find(id).map(this::toolCatalog));
    }

    private Tools toolCatalog(McpServer server) {
        var catalog = registry.findCatalog(server.id()).orElse(null);
        return new Tools(
                server.version(),
                catalog == null ? null : catalog.discoveredAt(),
                stale(server, catalog, clients.status(server)),
                catalog == null ? List.of() : catalog.tools());
    }

    @Override
    public AvailableTools availableTools(InvocationContext context) {
        var available = clients.availableTools(context, authorization.allowed(context));
        if (available.tools().size() > properties.getMaxTools()) {
            throw new IllegalStateException("MCP total tool limit exceeded");
        }
        for (var tool : available.tools()) {
            var source =
                    Map.<String, Object>of(
                            "kind",
                            "MCP_TOOL",
                            "serverId",
                            tool.serverId().toString(),
                            "serverName",
                            tool.serverName(),
                            "originalToolName",
                            tool.specification().name(),
                            "modelName",
                            tool.modelName(),
                            "configVersion",
                            tool.version());
            chatHistory.record(
                    HistoryRecords.metadata(context, "mcpTools", Map.of(tool.modelName(), source)));
            Map<String, Map<String, Object>> sources =
                    context.invocationParameters() == null
                            ? null
                            : context.invocationParameters().get(LlmClient.TOOL_SOURCES);
            if (sources != null) {
                sources.put(tool.modelName(), source);
            }
        }
        return available;
    }

    @Override
    public ToolExecutionResult executeTool(
            McpTool tool, ToolExecutionRequest request, InvocationContext context) {
        LlmClient.checkCancelled(context);
        var permitted= authorization.allowed(context);
        if(permitted!=null && !permitted.contains(tool.serverId()))
            return ToolExecutionResult.builder().isError(true).resultText("MCP server is no longer available in this run").build();
        try {
            var result = clients.execute(tool.serverId(), tool.version(), request, context);
            LlmClient.checkCancelled(context);
            return result;
        } catch (Exception error) {
            LlmClient.checkCancelled(context);
            if (error instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            if (McpToolException.hasCause(error, CancellationException.class)
                    || error instanceof InterruptedException) {
                throw new CancellationException("MCP invocation cancelled");
            }
            String message;
            if (McpToolException.hasCause(error, TimeoutException.class)) {
                message =
                        "MCP_RESULT_UNKNOWN: request timed out; the operation may have completed."
                                + " Do not retry automatically.";
            } else if (McpToolException.hasCause(error, ToolArgumentsException.class)) {
                message = "MCP_INVALID_ARGUMENT: tool arguments must be a valid JSON object";
            } else if (McpToolException.isDefinite(error)) {
                message =
                        "MCP_TOOL_ERROR: "
                                + Objects.toString(
                                        McpToolException.rootMessage(error), "MCP tool failed");
            } else {
                message =
                        "MCP_RESULT_UNKNOWN: connection failed; the operation may have completed."
                                + " Do not retry automatically.";
            }
            return ToolExecutionResult.builder()
                    .isError(true)
                    .resultText(
                            message.substring(
                                    0, Math.min(message.length(), properties.getMaxResultChars())))
                    .build();
        }
    }

    private McpServer create(McpConfigParser.Parsed parsed, McpServer old) {
        Instant now = Instant.now();
        return new McpServer(
                old == null ? UUID.randomUUID() : old.id(),
                old == null ? com.mindrails.backend.identity.ResourceAccess.creationOwner() : old.ownerId(),
                parsed.name(),
                secrets.seal(parsed.config(), old == null ? null : old.config()),
                parsed.enabled(),
                old == null ? 0 : old.version() + 1,
                old == null ? now : old.createdAt(),
                now);
    }

    private ServiceResult<McpServer> find(UUID id) {
        if (id == null) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "MCP server ID is required");
        }
        return registry.find(id)
                .<ServiceResult<McpServer>>map(ServiceResult::success)
                .orElseGet(
                        () -> ServiceResult.failure(ErrorCode.NOT_FOUND, "MCP server not found"));
    }

    private McpServerDetails details(McpServer server) {
        var catalog = registry.findCatalog(server.id()).orElse(null);
        var masked = secrets.masked(server.config());
        var status = clients.status(server);
        boolean hasSecrets =
                masked.env().containsValue(McpSecrets.KEEP)
                        || masked.headers().containsValue(McpSecrets.KEEP);
        return new McpServerDetails(
                server.id(),
                server.ownerId(),
                server.name(),
                server.config().type(),
                masked,
                server.enabled(),
                server.version(),
                server.createdAt(),
                server.updatedAt(),
                status,
                catalog == null ? 0 : catalog.tools().size(),
                catalog == null ? null : catalog.discoveredAt(),
                stale(server, catalog, status),
                hasSecrets);
    }

    private boolean stale(McpServer server, McpToolCatalog catalog, McpStatus status) {
        return catalog == null
                || catalog.version() != server.version()
                || status.state() != State.READY;
    }

    private <T> ServiceResult<T> storage(Supplier<ServiceResult<T>> operation) {
        try {
            return operation.get();
        } catch (IllegalArgumentException error) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, error.getMessage());
        } catch (DataAccessException | TransactionException error) {
            return ServiceResult.failure(ErrorCode.UNAVAILABLE, "MCP storage is unavailable");
        }
    }
}
