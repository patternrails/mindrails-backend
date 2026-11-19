package com.mindrails.backend.websearch;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.tool.ToolFailure;
import com.mindrails.backend.tool.web.WebSearchTool;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WebSearchClientTest {
    private final JsonMapper json = new JsonMapper();
    private final WebSearchProviderRegistry registry = mock(WebSearchProviderRegistry.class);
    private final WebSearchClient client = new WebSearchClient(registry, json);
    private final WebSearchTool tool = new WebSearchTool(client);
    private final AtomicReference<JsonNode> request = new AtomicReference<>();
    private final AtomicReference<String> auth = new AtomicReference<>();
    private HttpServer server;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
        client.close();
    }

    @Test
    void testConnectionUsesTheRequestedProviderEvenWhenItIsInactive() {
        var id = UUID.randomUUID();
        var provider = new WebSearchProvider(id, "DeepSeek", WebSearchProvider.DEEPSEEK,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/search", null,
                Map.of("need_content", true), 2000, false, 3, Instant.EPOCH, Instant.EPOCH);
        when(registry.connection(id)).thenReturn(Optional.of(
                new WebSearchProviderRegistry.Connection(provider, "private-test-key")));
        server.createContext("/search", exchange -> {
            try (exchange) {
                request.set(json.readTree(exchange.getRequestBody()));
                byte[] body = "{\"results\":[]}".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        var result = new WebSearchProviderService(registry, client).test(id);
        assertEquals(id, result.providerId());
        assertEquals(3, result.version());
        assertTrue(result.connected());
        assertEquals(1, request.get().path("count").asInt());
        assertFalse(request.get().path("need_content").asBoolean());
        verify(registry, never()).activeConnection();
    }

    @Test
    void bochaPreservesSearchParametersAndNormalizesPages() {
        configure(WebSearchProvider.BOCHA, null, Map.of("freshness", "oneWeek"), 200, """
                {"code":200,"data":{"webPages":{"totalEstimatedMatches":12,"value":[
                  {"name":"Guide","url":"https://example.org/guide","snippet":"Excerpt","summary":"Summary",
                   "siteName":"Example","datePublished":"2026-10-17T08:00:00+08:00"}
                ]}}}
                """);
        var result = tool.search("  query  ", null, 3, true, "example.org,openai.com", "other.org", null);
        assertEquals("Bearer private-test-key", auth.get());
        assertEquals("query", request.get().path("query").asText());
        assertEquals(3, request.get().path("count").asInt());
        assertEquals("example.org|openai.com", request.get().path("include").asText());
        assertEquals("other.org", request.get().path("exclude").asText());
        assertEquals("oneWeek", request.get().path("freshness").asText());
        assertTrue(request.get().path("summary").asBoolean());
        assertEquals(12, result.totalEstimatedMatches());
        assertEquals(new WebSearchClient.Page("Guide", "https://example.org/guide", "Excerpt", "Summary",
                "Example", "2026-10-17T08:00:00+08:00"), result.pages().getFirst());
    }

    @Test
    void deepSeekUsesSearchOperatorsAndNormalizesSnakeCaseFields() {
        configure(WebSearchProvider.DEEPSEEK, null, Map.of("need_content", true), 200, """
                {"results":[{"title":"Guide","url":"https://example.org/guide","snippet":"Excerpt",
                 "content":"Body","site_name":"Example","date":"2026-11-11"}]}
                """);
        var result = tool.search("guide", "2026-11-11", 3, true, "example.org", "other.org", null);
        assertEquals("guide site:example.org -site:other.org after:2026-11-10 before:2026-11-12",
                request.get().path("query").asText());
        assertTrue(request.get().path("need_content").asBoolean());
        assertEquals(new WebSearchClient.Page("Guide", "https://example.org/guide", "Excerpt", "Body", "Example",
                "2026-11-11"), result.pages().getFirst());
        assertThrows(IllegalArgumentException.class, () -> tool.search("界".repeat(129), null, 1, null, null, null, null));
    }

    @Test
    void openAiReadsCitationsAndSourcesWithoutReturningDuplicates() {
        configure(WebSearchProvider.OPENAI, "test-model", Map.of("search_context_size", "low"), 200, """
                {"status":"completed","output":[
                  {"type":"web_search_call","status":"completed","action":{"sources":[
                    {"url":"https://example.org/cited"},{"url":"https://example.org/another"}]}},
                  {"type":"message","content":[{"annotations":[
                    {"type":"url_citation","title":"Guide","url":"https://example.org/cited"}]}]}
                ]}
                """);
        var result = tool.search("guide", null, 1, true, "example.org", null, null);
        var body = request.get();
        assertEquals("test-model", body.path("model").asText());
        assertEquals("required", body.path("tool_choice").asText());
        assertFalse(body.path("store").asBoolean());
        var search = body.path("tools").get(0);
        assertEquals("web_search", search.path("type").asText());
        assertEquals("low", search.path("search_context_size").asText());
        assertEquals("example.org", search.path("filters").path("allowed_domains").get(0).asText());
        assertEquals(1, result.pages().size());
        assertEquals("Guide", result.pages().getFirst().title());
        assertTrue(result.truncated());
    }

    @Test
    void toolRemainsAvailableAndFailsClearlyWithoutAnActiveProvider() {
        when(registry.activeConnection()).thenReturn(Optional.empty());
        assertEquals("web_search", tool.buildTools(TestMetrics.disabled()).getFirst().name());
        var failure = assertThrows(ToolFailure.class, () -> tool.search("q", null, null, null, null, null, null));
        assertTrue(failure.getMessage().contains("No active web search provider"));
    }

    @Test
    void invalidParametersAreRejectedBeforeHttp() {
        active(WebSearchProvider.BOCHA, null, Map.of());
        assertThrows(IllegalArgumentException.class, () -> tool.search("q", null, 51, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> tool.search("q", "2026-10-18..2026-10-17", 1, null, null, null, null));
        assertThrows(IllegalArgumentException.class, () -> tool.search("q", null, 1, null, "https://example.org", null, null));
    }

    @Test
    void upstreamFailuresDoNotExposeKeysOrResponseBodies() {
        configure(WebSearchProvider.BOCHA, null, Map.of(), 429, "private-test-key sensitive upstream failure");
        var failure = assertThrows(ToolFailure.class, () -> tool.search("q", null, null, null, null, null, null));
        assertTrue(failure.getMessage().contains("rate limited"));
        assertFalse(failure.getMessage().contains("private-test-key"));
    }

    @Test
    void responseBodyIsBoundedBeforeJsonParsing() {
        configure(WebSearchProvider.BOCHA, null, Map.of(), 200, "x".repeat(2_000_001));
        var failure = assertThrows(ToolFailure.class, () -> tool.search("q", null, null, null, null, null, null));
        assertTrue(failure.getMessage().contains("size limit"));
    }

    @Test
    void cancellationInterruptsAnInflightSearch() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        server.createContext("/search", exchange -> {
            entered.countDown();
            try {
                release.await(5, TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally {
                exchange.close();
            }
        });
        active(WebSearchProvider.BOCHA, null, Map.of());
        var cancelled = new CompletableFuture<Void>();
        var context = InvocationContext.builder().invocationId(UUID.randomUUID()).invocationParameters(
                InvocationParameters.from(Map.of(LlmClient.CANCELLATION, cancelled))).build();
        try (var executor = Executors.newSingleThreadExecutor()) {
            var result = executor.submit(() -> tool.search("q", null, null, null, null, null, context));
            assertTrue(entered.await(2, TimeUnit.SECONDS));
            cancelled.complete(null);
            assertInstanceOf(CancellationException.class,
                    assertThrows(ExecutionException.class, () -> result.get(2, TimeUnit.SECONDS)).getCause());
        } finally {
            release.countDown();
        }
    }

    private void configure(String type, String model, Map<String, Object> options, int status, String response) {
        active(type, model, options);
        server.createContext("/search", exchange -> {
            try (exchange) {
                request.set(json.readTree(exchange.getRequestBody()));
                auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
                byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(status, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
    }

    private void active(String type, String model, Map<String, Object> options) {
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/search";
        var provider = new WebSearchProvider(UUID.randomUUID(), "test", type, endpoint, model, options, 5000,
                true, 0, Instant.EPOCH, Instant.EPOCH);
        when(registry.activeConnection()).thenReturn(Optional.of(new WebSearchProviderRegistry.Connection(provider, "private-test-key")));
    }
}
