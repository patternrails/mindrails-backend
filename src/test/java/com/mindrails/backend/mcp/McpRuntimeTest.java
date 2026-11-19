package com.mindrails.backend.mcp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.mcp.api.toolspec.McpTools;
import com.mindrails.backend.mcp.model.McpServerDetails;
import com.mindrails.backend.mcp.model.McpTool;
import com.mindrails.backend.mcp.registry.InMemoryMcpRegistry;
import com.mindrails.backend.mcp.service.DefaultMcpService;
import com.mindrails.backend.mcp.service.internal.McpClientManager;
import com.mindrails.backend.mcp.service.internal.McpProperties;
import com.mindrails.backend.mcp.service.internal.McpSecrets;
import com.mindrails.backend.metrics.InMemoryMetricsRepository;
import com.mindrails.backend.metrics.MetricsHealth;
import com.mindrails.backend.metrics.MetricsProperties;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.metrics.TestMetrics;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.service.tool.AiServiceTool;
import dev.langchain4j.service.tool.ToolExecutionResult;
import dev.langchain4j.service.tool.ToolProviderRequest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import com.mindrails.backend.mcp.service.McpToolAuthorization;

class McpRuntimeTest {
    static <T> T success(ServiceResult<T> result) {
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<T>) result).value();
    }

    static final String SCRIPT =
            Path.of("src/test/resources/fixtures/mcp/mock_server.py").toAbsolutePath().toString();

    static final class Fixture implements AutoCloseable {
        final InMemoryMcpRegistry registry = new InMemoryMcpRegistry();
        final McpProperties props = new McpProperties();
        final DefaultMcpService service;
        final McpClientManager manager;
        final McpTools tools;

        Fixture() {
            this(
                    com.mindrails.backend.metrics.TestMetrics.disabled(),
                    com.mindrails.backend.history.TestHistory.disabled());
        }

        Fixture(
                MetricsRecorder metricsRecorder,
                com.mindrails.backend.history.ChatHistory chatHistory) {
            manager = new McpClientManager(registry, new McpSecrets(""), props, metricsRecorder);
            service =
                    new DefaultMcpService(
                            registry, manager, new McpSecrets(""), props, chatHistory,
                            org.mockito.Mockito.mock(
                                    McpToolAuthorization.class, invocation -> null));
            tools = new McpTools(service);
        }

        final CompletableFuture<Void> cancellation = new CompletableFuture<>();
        final InvocationContext context =
                InvocationContext.builder()
                        .invocationId(UUID.randomUUID())
                        .chatMemoryId(UUID.randomUUID())
                        .invocationParameters(
                                InvocationParameters.from(LlmClient.CANCELLATION, cancellation))
                        .build();

        McpServerDetails stdio(String name, int timeout) {
            return success(
                            service.importConfig(
                                    Map.of(
                                            "mcpServers",
                                            Map.of(
                                                    name,
                                                    Map.of(
                                                            "command",
                                                            "python3",
                                                            "args",
                                                            List.of(SCRIPT, "--label", name),
                                                            "protocolVersion",
                                                            "2025-11-25",
                                                            "timeoutMs",
                                                            timeout))),
                                    "CREATE_ONLY",
                                    null))
                    .items()
                    .getFirst();
        }

        List<AiServiceTool> snapshot() {
            return tools.provideTools(
                            ToolProviderRequest.builder()
                                    .invocationContext(context)
                                    .userMessage(
                                            dev.langchain4j.data.message.UserMessage.from("test"))
                                    .build())
                    .aiServiceTools();
        }

        ToolExecutionResult call(
                List<AiServiceTool> snapshot, String server, String name, String args) {
            String alias =
                    McpTool.modelName(
                            registry.list().stream()
                                    .filter(s -> s.name().equals(server))
                                    .findFirst()
                                    .orElseThrow()
                                    .id(),
                            name);
            return snapshot.stream()
                    .filter(t -> t.name().equals(alias))
                    .findFirst()
                    .orElseThrow()
                    .toolExecutor()
                    .executeWithContext(
                            ToolExecutionRequest.builder()
                                    .id(UUID.randomUUID().toString())
                                    .name(alias)
                                    .arguments(args)
                                    .build(),
                            context);
        }

        public void close() {
            cancellation.complete(null);
            manager.close();
        }
    }

    static final class HttpMock implements AutoCloseable {
        final Process process;
        final String base;

        HttpMock() throws Exception {
            this(List.of());
        }

        HttpMock(List<String> options) throws Exception {
            var command =
                    new ArrayList<>(
                            List.of(
                                    "python3",
                                    SCRIPT,
                                    "--transport",
                                    "http",
                                    "--port",
                                    "0",
                                    "--label",
                                    "http-mock"));
            command.addAll(options);
            process = new ProcessBuilder(command).start();
            var ready = new CompletableFuture<String>();
            Thread.startVirtualThread(
                    () -> {
                        try {
                            ready.complete(
                                    new BufferedReader(
                                                    new InputStreamReader(process.getErrorStream()))
                                            .readLine());
                        } catch (IOException error) {
                            ready.completeExceptionally(error);
                        }
                    });
            String line;
            try {
                line = ready.get(10, TimeUnit.SECONDS);
            } catch (Exception error) {
                process.destroyForcibly();
                throw error;
            }
            var match = java.util.regex.Pattern.compile("http://127.0.0.1:(\\d+)").matcher(line);
            assertTrue(match.find(), line);
            base = match.group();
        }

        public void close() {
            process.destroyForcibly();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"stdio", "http", "sse"})
    void discoversPaginatedToolsCallsErrorsAndNormalizesResults(String transport) throws Exception {
        try (var f = new Fixture();
                var http = transport.equals("stdio") ? null : new HttpMock()) {
            if (http == null) {
                f.stdio("server", 30000);
            } else {
                f.service.importConfig(
                        Map.of(
                                "servers",
                                Map.of(
                                        "server",
                                        Map.of(
                                                "type",
                                                transport,
                                                "url",
                                                http.base
                                                        + (transport.equals("sse")
                                                                ? "/sse"
                                                                : "/mcp"),
                                                "protocolVersion",
                                                "2025-11-25"))),
                        "CREATE_ONLY",
                        null);
            }
            var snapshot = f.snapshot();
            assertEquals(7, snapshot.size());
            assertEquals(
                    7,
                    f.registry
                            .findCatalog(f.registry.list().getFirst().id())
                            .orElseThrow()
                            .tools()
                            .size());
            assertEquals(
                    "5",
                    f.call(snapshot, "server", "add", "{\"a\":2,\"b\":3}").resultText().trim());
            var failure = f.call(snapshot, "server", "fail", "{}");
            assertTrue(failure.isError());
            assertTrue(
                    failure.resultText().contains("Deliberate mock failure"), failure.resultText());
            assertTrue(
                    f.call(snapshot, "server", "structured", "{}")
                            .resultText()
                            .contains("\"ok\":true"));
            assertTrue(f.call(snapshot, "server", "binary", "{}").resultText().contains("omitted"));
            assertFalse(f.call(snapshot, "server", "binary", "{}").resultText().contains("bW9jaw"));
            assertTrue(
                    f.call(snapshot, "server", "large", "{}")
                            .resultText()
                            .endsWith("[MCP result truncated]"));
            assertTrue(f.call(snapshot, "server", "add", "{\"a\":\"bad\",\"b\":1}").isError());
        }
    }

    @Test
    void sameNamesRouteCorrectlyAndChangedOrDeletedConfigurationCannotExecute() {
        try (var f = new Fixture()) {
            var a = f.stdio("first", 30000);
            f.stdio("second", 30000);
            var snapshot = f.snapshot();
            assertEquals(14, snapshot.size());
            assertTrue(snapshot.stream().allMatch(t -> t.name().length() <= 64));
            assertEquals(14, snapshot.stream().map(AiServiceTool::name).distinct().count());
            assertEquals(
                    "first: hello",
                    f.call(snapshot, "first", "echo", "{\"text\":\"hello\"}").resultText().trim());
            assertEquals(
                    "second: hello",
                    f.call(snapshot, "second", "echo", "{\"text\":\"hello\"}").resultText().trim());
            assertInstanceOf(
                    ServiceResult.Success.class,
                    f.service.update(a.id(), a.version(), null, false));
            assertTrue(
                    f.call(snapshot, "first", "echo", "{\"text\":\"hello\"}")
                            .resultText()
                            .startsWith("MCP_CONFIG_CHANGED"));
            assertEquals(7, f.snapshot().size());
        }
    }

    @Test
    void timeoutIsAnErrorAndNeverRetried() {
        try (var f = new Fixture()) {
            f.stdio("short", 1000);
            var snapshot = f.snapshot();
            long started = System.nanoTime();
            var result = f.call(snapshot, "short", "slow", "{\"seconds\":5}");
            assertTrue(result.isError(), result.resultText());
            assertTrue(result.resultText().contains("MCP_RESULT_UNKNOWN"), result.resultText());
            assertTrue(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started) < 3000);
            assertEquals(
                    "short: alive",
                    f.call(snapshot, "short", "echo", "{\"text\":\"alive\"}").resultText().trim());
        }
    }

    @Test
    void http404NeverReplaysTheBusinessCallAndSsePostResponsesAreSupported() throws Exception {
        for (boolean failure : List.of(false, true)) {
            try (var f = new Fixture();
                    var http =
                            new HttpMock(
                                    failure
                                            ? List.of("--call-status", "404")
                                            : List.of("--http-sse-responses"))) {
                f.service.importConfig(
                        Map.of("servers", Map.of("remote", Map.of("url", http.base + "/mcp"))),
                        "CREATE_ONLY",
                        null);
                var result = f.call(f.snapshot(), "remote", "add", "{\"a\":2,\"b\":4}");
                if (failure) {
                    assertTrue(result.isError());
                    assertTrue(result.resultText().contains("MCP_RESULT_UNKNOWN"));
                } else {
                    assertEquals("6", result.resultText().trim());
                }
                var response =
                        java.net.http.HttpClient.newHttpClient()
                                .send(
                                        java.net.http.HttpRequest.newBuilder(
                                                        java.net.URI.create(http.base + "/stats"))
                                                .build(),
                                        java.net.http.HttpResponse.BodyHandlers.ofString());
                var stats = JsonMapper.builder().build().readTree(response.body());
                assertEquals(1, stats.path("tools/call").asInt());
            }
        }
    }

    @Test
    void cancellationDoesNotCloseTheSharedClientAndMetricsCountOnlyMcpCalls() throws Exception {
        var properties = new MetricsProperties();
        var repository =
                new InMemoryMetricsRepository(new MetricsProperties(), new MetricsHealth());
        try (var metrics = new TestMetrics(properties, repository, new MetricsHealth(), false);
                var f =
                        new Fixture(
                                metrics.recorder(),
                                com.mindrails.backend.history.TestHistory.disabled())) {
            f.stdio("shared", 30000);
            var snapshot = f.snapshot();
            {
                var run = metrics.begin(null, null, "TEST");
                run.parameters().put(LlmClient.CANCELLATION, f.cancellation);
                run.parameters()
                        .asMap()
                        .forEach((key, value) -> f.context.invocationParameters().put(key, value));
                var pending =
                        CompletableFuture.supplyAsync(
                                () -> f.call(snapshot, "shared", "slow", "{\"seconds\":10}"));
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
                while (metrics.buffer().size() > 0 && System.nanoTime() < deadline) {
                    metrics.flush();
                    if (repository.snapshots().stream().anyMatch(s -> s.mcpToolCallCount() > 0)) {
                        break;
                    }
                    Thread.sleep(10);
                }
                f.cancellation.complete(null);
                assertThrows(ExecutionException.class, () -> pending.get(2, TimeUnit.SECONDS));
                run.finish("CANCELLED", "USER_CANCELLED", null);
                metrics.flush();
                var saved = repository.snapshots().getFirst();
                assertEquals(1, saved.mcpToolCallCount());
                assertEquals(0, saved.functionCallCount());
                assertEquals("CANCELLED", saved.capabilityCalls().getFirst().status());
                assertNotNull(saved.capabilityCalls().getFirst().serverId());
                var another = new CompletableFuture<Void>();
                var context =
                        InvocationContext.builder()
                                .invocationId(UUID.randomUUID())
                                .chatMemoryId(UUID.randomUUID())
                                .invocationParameters(
                                        InvocationParameters.from(LlmClient.CANCELLATION, another))
                                .build();
                var tool =
                        snapshot.stream()
                                .filter(
                                        t ->
                                                t.name()
                                                        .equals(
                                                                McpTool.modelName(
                                                                        f.registry
                                                                                .list()
                                                                                .getFirst()
                                                                                .id(),
                                                                        "echo")))
                                .findFirst()
                                .orElseThrow();
                assertTrue(
                        tool.toolExecutor()
                                .executeWithContext(
                                        ToolExecutionRequest.builder()
                                                .id("other")
                                                .name(tool.name())
                                                .arguments("{\"text\":\"other\"}")
                                                .build(),
                                        context)
                                .resultText()
                                .contains("other"));
                another.complete(null);
            }
        }
    }
}
