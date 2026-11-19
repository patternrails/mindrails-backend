package com.mindrails.backend.image;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.common.LimitedResponseBody;
import dev.langchain4j.invocation.InvocationContext;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

import java.net.*;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;

/** Both vendors use the generations endpoint, but return different image representations. */
@Component
public class ImageGenerationClient implements AutoCloseable {
    private static final int MAX_IMAGE = 24 * 1024 * 1024;
    private final JsonMapper json = new JsonMapper();
    private final HttpClient http;

    public ImageGenerationClient() {
        this(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NEVER).build());
    }
    ImageGenerationClient(HttpClient http) { this.http = http; }
    @Override public void close() { http.close(); }

    public record Image(byte[] bytes, String extension) {}

    public void checkModel(ImageProvider.Config config) {
        var provider = config.info();
        var endpoint = URI.create(provider.url());
        boolean qwen = "qwen".equals(provider.providerName());
        String model = URLEncoder.encode(provider.model(), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        String path = endpoint.getRawPath();
        String suffix = "/images/generations";
        if (!path.endsWith(suffix)) {
            throw failure("Cannot derive a model lookup from the configured generations URL");
        }
        String base = path.substring(0, path.length() - suffix.length());
        if (qwen && base.endsWith("/compatible-mode/v1")) {
            base = base.substring(0, base.length() - "/compatible-mode/v1".length()) + "/api/v1";
        }
        String lookup = qwen ? base + "/models?model=" + model : base + "/models/" + model;
        URI uri = URI.create(endpoint.getScheme() + "://" + endpoint.getRawAuthority() + lookup);
        int timeoutMillis = Math.min(provider.timeoutMillis(), 15_000);
        var request = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMillis))
                .header("Authorization", "Bearer " + config.apiKey()).GET().build();
        var result = json.readTree(send(request, 1024 * 1024,
                System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMillis), null));
        if (qwen) {
            var models = result.path("output").path("models");
            if (!result.path("success").asBoolean(false) || !models.isArray()) {
                throw failure("Image provider returned an invalid model lookup response");
            }
            for (var entry : models) {
                if (provider.model().equals(entry.path("model").asText())
                        && !excludesImage(entry.path("inference_metadata").path("response_modality"))) return;
            }
        } else if ("model".equals(result.path("object").asText())
                && provider.model().equals(result.path("id").asText())) {
            return;
        }
        throw failure("The configured image model was not found in the provider catalog");
    }

    private static boolean excludesImage(tools.jackson.databind.JsonNode modalities) {
        if (!modalities.isArray() || modalities.isEmpty()) return false;
        for (var modality : modalities) {
            if ("image".equalsIgnoreCase(modality.asText())) return false;
        }
        return true;
    }

    public Image generate(ImageProvider.Config config, String prompt, InvocationContext context) {
        return generate(config, prompt, ImageGenerationOptions.defaults(), context).getFirst();
    }

    public List<Image> generate(ImageProvider.Config config, String prompt,
            ImageGenerationOptions options, InvocationContext context) {
        var p = config.info();
        var body = options.request(p, prompt); // Validate before any paid request.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(p.timeoutMillis());
        var request = HttpRequest.newBuilder(URI.create(p.url())).timeout(Duration.ofMillis(p.timeoutMillis()))
                .header("Content-Type", "application/json").header("Authorization", "Bearer " + config.apiKey())
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        var response = send(request, 36 * 1024 * 1024, deadline, context);
        var data = json.readTree(response).path("data");
        if (!data.isArray() || data.size() != options.count()) throw failure("Provider returned an unexpected image count");
        var images = new ArrayList<Image>();
        long totalBytes = 0;
        for (var item : data) {
            byte[] bytes;
            if (p.providerName().equals("qwen")) {
                URI url;
                try { url = URI.create(item.path("url").asString()); }
                catch (RuntimeException invalid) { throw failure("Provider returned an invalid image URL"); }
                validateDownload(url);
                // Never forward the provider's Authorization header to an image host.
                bytes = send(HttpRequest.newBuilder(url).timeout(Duration.ofMillis(p.timeoutMillis())).GET().build(),
                        MAX_IMAGE - (int) totalBytes, deadline, context);
            } else {
                try {
                    String encoded = item.path("b64_json").asString();
                    if (encoded.length() > MAX_IMAGE * 4L / 3 + 4) throw new IllegalArgumentException();
                    bytes = Base64.getDecoder().decode(encoded);
                } catch (RuntimeException invalid) { throw failure("Provider returned invalid image data"); }
            }
            LlmClient.checkCancelled(context);
            totalBytes += bytes.length;
            if (bytes.length == 0 || totalBytes > MAX_IMAGE) throw failure("Image batch exceeds the 24 MiB size limit");
            images.add(new Image(bytes, extension(bytes)));
        }
        return List.copyOf(images);
    }

    static String extension(byte[] bytes) {
        if (bytes.length > 8 && bytes[0] == (byte) 0x89 && bytes[1] == 'P' && bytes[2] == 'N' && bytes[3] == 'G'
                && bytes[4] == 13 && bytes[5] == 10 && bytes[6] == 26 && bytes[7] == 10) return "png";
        if (bytes.length > 3 && bytes[0] == (byte) 0xff && bytes[1] == (byte) 0xd8 && bytes[2] == (byte) 0xff) return "jpg";
        if (bytes.length > 12 && bytes[0] == 'R' && bytes[1] == 'I' && bytes[2] == 'F' && bytes[3] == 'F'
                && bytes[8] == 'W' && bytes[9] == 'E' && bytes[10] == 'B' && bytes[11] == 'P') return "webp";
        throw failure("Provider output is not a supported PNG, JPEG or WebP image");
    }

    private static void validateDownload(URI uri) {
        if (!"https".equalsIgnoreCase(uri.getScheme()) || uri.getHost() == null
                || uri.getRawUserInfo() != null || uri.getFragment() != null)
            throw failure("Image download requires a public HTTPS URL");
        try {
            for (var address : InetAddress.getAllByName(uri.getHost())) {
                if (address.isAnyLocalAddress() || address.isLoopbackAddress() || address.isLinkLocalAddress()
                        || address.isSiteLocalAddress() || address.isMulticastAddress()
                        || (address.getAddress()[0] & 0xfe) == 0xfc)
                    throw failure("Image download requires a public HTTPS URL");
            }
        } catch (UnknownHostException error) { throw failure("Image host could not be resolved"); }
    }

    private byte[] send(HttpRequest request, int limit, long deadline, InvocationContext context) {
        var future = http.sendAsync(request, ignored -> new LimitedResponseBody(limit));
        try {
            while (true) {
                LlmClient.checkCancelled(context);
                if (System.nanoTime() >= deadline) throw failure("Image generation timed out");
                try {
                    var response = future.get(100, TimeUnit.MILLISECONDS);
                    if (response.statusCode() < 200 || response.statusCode() >= 300)
                        throw failure("Image provider request failed (HTTP " + response.statusCode() + ")");
                    return response.body();
                } catch (TimeoutException waiting) { /* bounded cancellation polling */ }
            }
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw new CancellationException("Image generation interrupted");
        } catch (ExecutionException error) { throw failure("Image provider connection or response failed"); }
        finally { future.cancel(true); }
    }

    private static ImageGenerationException failure(String message) {
        return new ImageGenerationException(com.mindrails.backend.common.ServiceResult.ErrorCode.UNAVAILABLE, message);
    }

}
