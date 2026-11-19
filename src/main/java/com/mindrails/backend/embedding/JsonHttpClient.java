package com.mindrails.backend.embedding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CancellationException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Bounded network calls. Provider response bodies and headers never enter exceptions or logs. */
public final class JsonHttpClient implements AutoCloseable {
    private final HttpClient client;
    private final ObjectMapper mapper;
    private final Duration timeout;

    public JsonHttpClient(ObjectMapper mapper, int timeoutMillis) {
        this.mapper = mapper;
        timeout = Duration.ofMillis(timeoutMillis);
        client = HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    public record Response(int status, JsonNode body) {
        public JsonNode requireSuccess() {
            if (status < 200 || status >= 300) throw new IllegalStateException("Remote memory service HTTP " + status);
            return body;
        }
    }

    public Response request(String method, URI uri, Object body, Map<String, String> headers) {
        checkActive();
        var builder = HttpRequest.newBuilder(uri).timeout(timeout).header("Content-Type", "application/json");
        headers.forEach(builder::header);
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(body)));
        try {
            var response = client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            // An interrupted or cancelled invocation must not advance to its next operation.
            checkActive();
            if (response.statusCode() < 200 || response.statusCode() >= 300) {
                return new Response(response.statusCode(), mapper.createObjectNode());
            }
            try { return new Response(response.statusCode(), mapper.readTree(response.body())); }
            catch (RuntimeException invalid) { throw new IllegalStateException("Remote memory service returned invalid JSON"); }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new CancellationException("Memory request interrupted");
        } catch (IOException failure) {
            throw new IllegalStateException("Remote memory service connection failed or timed out");
        }
    }

    public static void checkActive() {
        if (Thread.currentThread().isInterrupted()) throw new CancellationException("Memory request interrupted");
    }

    @Override public void close() { client.shutdownNow(); }
}
