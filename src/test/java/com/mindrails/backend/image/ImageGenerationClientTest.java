package com.mindrails.backend.image;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mindrails.backend.agent.model.LlmClient;
import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.invocation.*;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;
import java.net.*;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;

class ImageGenerationClientTest {
    static final byte[] PNG = Base64.getDecoder().decode("iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aN1sAAAAASUVORK5CYII=");
    static ImageProvider.Config config(String vendor, String url) {
        return new ImageProvider.Config(new ImageProvider.Info(UUID.randomUUID(), "test", vendor, url, "test-model",
                true, true, true, "1024x1024", true, 2000, true, true, null, 1L, "OK", 0), "private-api-key");
    }

    @Test void modelChecksUseAuthenticatedGetWithoutCallingGenerations() throws Exception {
        var requests = new ArrayList<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try (exchange) {
                requests.add(exchange.getRequestMethod() + " " + exchange.getRequestURI() + " "
                        + exchange.getRequestHeaders().getFirst("Authorization"));
                String response = switch (exchange.getRequestURI().getPath()) {
                    case "/proxy/v1/models/test-model" -> """
                            {"object":"model","id":"test-model"}
                            """;
                    case "/api/v1/models" -> """
                            {"success":true,"output":{"models":[{"model":"test-model",
                            "inference_metadata":{"response_modality":["Image"]}}]}}
                            """;
                    default -> "{}";
                };
                byte[] bytes = response.getBytes(java.nio.charset.StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(200, bytes.length);
                exchange.getResponseBody().write(bytes);
            }
        });
        server.start();
        try (var client = new ImageGenerationClient()) {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            client.checkModel(config("openai", base + "/proxy/v1/images/generations"));
            client.checkModel(config("qwen", base + "/compatible-mode/v1/images/generations"));
            assertEquals(List.of("GET /proxy/v1/models/test-model Bearer private-api-key",
                    "GET /api/v1/models?model=test-model Bearer private-api-key"), requests);
            assertThrows(ImageGenerationException.class, () -> client.checkModel(config("openai", base + "/custom")));
            assertThrows(ImageGenerationException.class,
                    () -> client.checkModel(config("openai", base + "/v1/images/generations")));
        } finally {
            server.stop(0);
        }
    }

    @Test void openAiPostsTheConfiguredRequestAndDecodesBase64WithoutExposingIt() throws Exception {
        var body = new AtomicReference<String>(); var auth = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/images/generations", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            auth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            byte[] response = ("{\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}]}").getBytes();
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try (var client = new ImageGenerationClient()) {
            var image = client.generate(config("openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/images/generations"), "draw a cat", null);
            assertArrayEquals(PNG, image.bytes()); assertEquals("png", image.extension());
            var json = new JsonMapper().readTree(body.get());
            assertEquals("draw a cat", json.path("prompt").asString()); assertEquals(1, json.path("n").asInt());
            assertEquals("test-model", json.path("model").asString()); assertFalse(json.has("prompt_extend"));
            assertEquals("Bearer private-api-key", auth.get());
        } finally { server.stop(0); }
    }

    @SuppressWarnings("unchecked")
    @Test void qwenDownloadsPublicUrlWithoutSendingTheApiKeyToTheImageHost() {
        var http = mock(HttpClient.class);
        var jsonResponse = (HttpResponse<byte[]>) mock(HttpResponse.class);
        when(jsonResponse.statusCode()).thenReturn(200);
        when(jsonResponse.body()).thenReturn("{\"data\":[{\"url\":\"https://8.8.8.8/image.png?signature=private\"}]}".getBytes());
        var imageResponse = (HttpResponse<byte[]>) mock(HttpResponse.class);
        when(imageResponse.statusCode()).thenReturn(200); when(imageResponse.body()).thenReturn(PNG);
        var calls = new ArrayList<HttpRequest>();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            var request = (HttpRequest) invocation.getArgument(0); calls.add(request);
            return CompletableFuture.completedFuture(calls.size() == 1 ? jsonResponse : imageResponse);
        });
        var image = new ImageGenerationClient(http).generate(config("qwen", "https://provider.example/images/generations"), "画一只猫", null);
        assertArrayEquals(PNG, image.bytes()); assertEquals(2, calls.size());
        assertTrue(calls.get(0).headers().firstValue("Authorization").isPresent());
        assertTrue(calls.get(1).headers().firstValue("Authorization").isEmpty());
        assertEquals("GET", calls.get(1).method());
    }

    @SuppressWarnings("unchecked")
    @Test void qwenRejectsPrivateDownloadAddressesAndDoesNotFollowRedirects() {
        var http = mock(HttpClient.class); var response = (HttpResponse<byte[]>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"url\":\"https://127.0.0.1/private\"}]}".getBytes());
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
        assertThrows(ImageGenerationException.class, () -> new ImageGenerationClient(http).generate(config("qwen", "https://example.test/images/generations"), "cat", null));
        verify(http, times(1)).sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
        when(response.statusCode()).thenReturn(302);
        assertThrows(ImageGenerationException.class, () -> new ImageGenerationClient(http).generate(config("openai", "https://example.test/images/generations"), "cat", null));
    }

    @SuppressWarnings("unchecked")
    @Test void cancellationAbortsPendingImageRequest() {
        var http = mock(HttpClient.class); var pending = new CompletableFuture<HttpResponse<byte[]>>();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(pending);
        var cancelled = CompletableFuture.<Void>completedFuture(null);
        var context = InvocationContext.builder().invocationParameters(InvocationParameters.from(Map.of(LlmClient.CANCELLATION, cancelled))).build();
        assertThrows(CancellationException.class, () -> new ImageGenerationClient(http).generate(config("openai", "https://example.test/images/generations"), "cat", context));
        assertTrue(pending.isCancelled());
    }

    @SuppressWarnings("unchecked")
    @Test void errorBodiesAndNonImagesDoNotEscapeAsGeneratedImages() {
        var http = mock(HttpClient.class); var response = (HttpResponse<byte[]>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(401); when(response.body()).thenReturn("private-api-key remote diagnostic".getBytes());
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenReturn(CompletableFuture.completedFuture(response));
        var client = new ImageGenerationClient(http);
        var error = assertThrows(ImageGenerationException.class, () -> client.generate(config("openai", "https://example.test/images/generations"), "cat", null));
        assertFalse(error.getMessage().contains("private-api-key"));
        assertThrows(ImageGenerationException.class, () -> ImageGenerationClient.extension("<html>not an image</html>".getBytes()));
    }
    @Test void openAiSendsOutputOptionsAndDecodesEveryRequestedImage() throws Exception {
        var body = new AtomicReference<String>();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/images/generations", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes()));
            String item = "{\"b64_json\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}";
            byte[] response = ("{\"data\":[" + item + "," + item + "]}").getBytes();
            exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try (var client = new ImageGenerationClient()) {
            var options = ImageGenerationOptions.builder().width(1536).height(864).n(2).quality("max")
                    .background("transparent").outputFormat("webp").outputCompression(80).moderation("auto").build();
            var images = client.generate(config("openai", "http://127.0.0.1:" + server.getAddress().getPort() + "/images/generations"), "draw", options, null);
            assertEquals(2, images.size()); images.forEach(image -> assertArrayEquals(PNG, image.bytes()));
            var request = new JsonMapper().readTree(body.get());
            assertEquals("1536x864", request.path("size").asString()); assertEquals(2, request.path("n").asInt());
            assertEquals("max", request.path("quality").asString()); assertEquals("transparent", request.path("background").asString());
            assertEquals("webp", request.path("output_format").asString()); assertEquals(80, request.path("output_compression").asInt());
            assertEquals("auto", request.path("moderation").asString()); assertFalse(request.has("seed"));
        } finally { server.stop(0); }
    }

    @SuppressWarnings("unchecked")
    @Test void qwenDownloadsAllImagesAndKeepsEnhancementOptionsOnTheGenerationRequestOnly() {
        var http = mock(HttpClient.class); var response = (HttpResponse<byte[]>) mock(HttpResponse.class);
        when(response.statusCode()).thenReturn(200);
        when(response.body()).thenReturn("{\"data\":[{\"url\":\"https://8.8.8.8/a.png\"},{\"url\":\"https://8.8.8.8/b.png\"}]}".getBytes());
        var image = (HttpResponse<byte[]>) mock(HttpResponse.class); when(image.statusCode()).thenReturn(200); when(image.body()).thenReturn(PNG);
        var calls = new ArrayList<HttpRequest>();
        when(http.sendAsync(any(HttpRequest.class), any(HttpResponse.BodyHandler.class))).thenAnswer(invocation -> {
            calls.add(invocation.getArgument(0)); return CompletableFuture.completedFuture(calls.size() == 1 ? response : image);
        });
        var options = ImageGenerationOptions.builder().n(2).promptExtend(false).seed(42L).watermark(true).build();
        var images = new ImageGenerationClient(http).generate(config("qwen", "https://provider.example"), "draw", options, null);
        assertEquals(2, images.size()); assertEquals(3, calls.size());
        calls.subList(1, 3).forEach(request -> assertTrue(request.headers().firstValue("Authorization").isEmpty()));
    }

}
