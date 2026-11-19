package com.mindrails.backend.embedding;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class EmbeddingProviderRegistryTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    @Test void resolvesOnlySelectedCredentialsAndSupportsCompatibleQwenAndOpenAi() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var request = new AtomicReference<JsonNode>();
        var authorization = new AtomicReference<String>();
        server.createContext("/v1/embeddings", exchange -> {
            request.set(mapper.readTree(exchange.getRequestBody()));
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] body = "{\"data\":[{\"index\":0,\"embedding\":[1,0,0]}]}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try (var registry = registry(server.getAddress().getPort(), 0)) {
            var qwen = registry.require("qwen");
            assertEquals(List.of(1.0, 0.0, 0.0), qwen.embed("项目约定"));
            assertEquals("Bearer test-secret", authorization.get());
            assertEquals("text-embedding-v4", request.get().path("model").asText());
            assertEquals(3, request.get().path("dimensions").asInt());
            assertEquals("项目约定", request.get().path("input").get(0).asText());
            assertSame(qwen, registry.require("qwen"));
            assertFalse(qwen.toString().contains("test-secret"));
            assertThrows(IllegalArgumentException.class, () -> registry.require("openai").embed("hello"));
        } finally { server.stop(0); }
    }

    @Test void retriesTransientErrorsButNeverLeaksResponseBodiesOrAcceptsWrongDimensions() throws Exception {
        var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", exchange -> {
            boolean first = count.incrementAndGet() == 1;
            byte[] body = (first ? "test-secret private provider error" : "{\"data\":[{\"embedding\":[1,0]}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(first ? 429 : 200, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try (var registry = registry(server.getAddress().getPort(), 1)) {
            var failure = assertThrows(IllegalStateException.class, () -> registry.require("qwen").embed("hello"));
            assertEquals(2, count.get());
            assertTrue(failure.getMessage().contains("dimension"));
            assertFalse(failure.toString().contains("test-secret"));
        } finally { server.stop(0); }
    }

    @Test void openAiUsesItsOwnModelAndKeyAndErrorsDoNotExposeProviderBodies() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var model = new AtomicReference<String>();
        server.createContext("/v1/embeddings", exchange -> {
            model.set(mapper.readTree(exchange.getRequestBody()).path("model").asText());
            byte[] body = "private-token internal error details".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(401, body.length); exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        var provider = new EmbeddingProviderDefinition("openai", "openai", "text-embedding-3-small",
                "http://127.0.0.1:" + server.getAddress().getPort() + "/v1",
                "private-token", 1536, 1000, 0);
        try (var registry = TestEmbeddingCatalog.open(mapper, provider)) {
            var failure = assertThrows(IllegalStateException.class, () -> registry.require("openai").embed("hello"));
            assertEquals("text-embedding-3-small", model.get());
            assertEquals("Remote memory service HTTP 401", failure.getMessage());
            assertFalse(failure.toString().contains("private-token"));
        } finally { server.stop(0); }
    }

    private EmbeddingProviderRegistry registry(int port, int retries) {
        return TestEmbeddingCatalog.open(mapper,
                new EmbeddingProviderDefinition("qwen", "qwen", "text-embedding-v4",
                        "http://127.0.0.1:" + port + "/v1", "test-secret", 3, 1000, retries),
                new EmbeddingProviderDefinition("openai", "openai", "text-embedding-3-small",
                        "https://api.openai.com/v1", null, 1536, 1000, retries));
    }
}
