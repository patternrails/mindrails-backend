package com.mindrails.backend.provider;

import com.mindrails.backend.common.LimitedResponseBody;
import com.mindrails.backend.common.ServiceResponseException;
import com.mindrails.backend.common.ServiceResponses;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.embedding.EmbeddingProviderDefinition;
import com.mindrails.backend.embedding.EmbeddingProviderService;
import com.mindrails.backend.image.ImageProvider;
import com.mindrails.backend.image.ImageProviderService;
import com.mindrails.backend.identity.Identity;
import com.mindrails.backend.identity.IdentityService;
import com.mindrails.backend.websearch.WebSearchProvider;
import com.mindrails.backend.websearch.WebSearchProviderService;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import static com.mindrails.backend.common.ServiceResult.ErrorCode.*;

@Service
public final class QuickSetupService {
    public enum Kind { language, embedding, image, webSearch }
    public record Status(boolean empty, boolean hasEmbedding) {}
    public record Draft(String type, String endpoint, String apiKey, String model) {
        @Override public String toString() { return "QuickSetup.Draft[redacted]"; }
    }
    public record Test(boolean connected, long latencyMs, String receipt, String model,
            boolean needsModel, List<String> models) {}
    public record Verified(Draft draft, String receipt) {
        @Override public String toString() { return "QuickSetup.Verified[redacted]"; }
    }
    public record Setup(Verified language, Verified embedding, Verified image, Verified webSearch, String preferredLanguage) {}
    private record Passed(Draft draft, long latencyMs) {}

    private static final Map<String, String> LANGUAGE_MODELS = Map.of(
            "Anthropic", "claude-haiku-4-5-20251001", "OpenAi", "gpt-5-mini",
            "Qwen", "qwen-plus", "GoogleAiGemini", "gemini-3.8-flash");
    private static final Duration RECEIPT_LIFETIME = Duration.ofMinutes(30);
    private final ProviderService language;
    private final ProvidersConfiguration configurations;
    private final EmbeddingProviderService embeddings;
    private final ImageProviderService images;
    private final WebSearchProviderService search;
    private final IdentityService identity;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ObjectMapper json;
    private final SecretKeySpec receiptKey;

    public QuickSetupService(ProviderService language, ProvidersConfiguration configurations,
            EmbeddingProviderService embeddings, ImageProviderService images, WebSearchProviderService search, IdentityService identity,
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("persistenceTransactionManager") PlatformTransactionManager manager, ObjectMapper json) {
        this.language = language;
        this.configurations = configurations;
        this.embeddings = embeddings;
        this.images = images;
        this.search = search;
        this.identity = identity;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(manager);
        this.json = json;
        byte[] key = new byte[32];
        new SecureRandom().nextBytes(key);
        this.receiptKey = new SecretKeySpec(key, "HmacSHA256");
    }

    public Status status() {
        return jdbc.queryForObject("""
                SELECT EXISTS(SELECT 1 FROM embedding_providers) AS embedding_configured,
                       EXISTS(SELECT 1 FROM providers) OR EXISTS(SELECT 1 FROM image_generation_providers)
                           OR EXISTS(SELECT 1 FROM web_search_providers) AS other_configured
                """, (row, i) -> {
            boolean hasEmbedding = row.getBoolean("embedding_configured");
            return new Status(!hasEmbedding && !row.getBoolean("other_configured"), hasEmbedding);
        });
    }

    public Test test(UUID actor, Kind kind, Draft input) {
        requireEmptyEmbeddings(status().hasEmbedding());
        var draft = normalize(kind, input);
        if (kind == Kind.language && draft.type().equals("OpenAiCompiable") && draft.model().isEmpty()) {
            var models = listModels(draft);
            if (models.size() != 1) return new Test(false, 0, null, null, true, models);
            draft = new Draft(draft.type(), draft.endpoint(), draft.apiKey(), models.getFirst());
        }
        var result = switch (kind) {
            case language -> {
                var provider = configurations.buildProvider(language(draft, "Quick setup"));
                yield ProviderConnectionTest.run(() -> ProviderConnectionTest.language(provider));
            }
            case embedding -> embeddings.probe(embedding(draft));
            case image -> images.probe(image(draft, "Quick setup"));
            case webSearch -> search.probe(search(draft, "Quick setup"));
        };
        String receipt = result.connected() ? issueReceipt(actor, kind, draft, result.latencyMs()) : null;
        return new Test(result.connected(), result.latencyMs(), receipt, draft.model(), false, List.of());
    }

    public Identity.Profile save(Identity.Current actor, Setup input) {
        if (input == null) throw invalid("Quick setup configuration is required");
        var llm = checked(actor.id(), Kind.language, input.language()).draft();
        var embedding = checked(actor.id(), Kind.embedding, input.embedding()).draft();
        var image = input.image() == null ? null : checked(actor.id(), Kind.image, input.image());
        var web = input.webSearch() == null ? null : checked(actor.id(), Kind.webSearch, input.webSearch()).draft();

        // The existing CRUD services take these monitors before entering the registries.
        // Keep them through commit so they always write against the published catalogs.
        synchronized (language) {
            synchronized (embeddings) {
                return transaction.execute(status -> {
                    requireEmptyEmbeddings(!jdbc.queryForList(
                            "SELECT name FROM embedding_providers FOR UPDATE", String.class).isEmpty());
                    var definition = embedding(embedding);
                    requireSaved(embeddings.save(definition.name(), definition, true));
                    requireSaved(language.save(UUID.randomUUID(), language(llm, availableName(Kind.language, llm)), true, true));
                    if (image != null) {
                        var draft = image.draft();
                        images.createTested(image(draft, availableName(Kind.image, draft)), image.latencyMs());
                    }
                    if (web != null) {
                        search.activate(search.save(null, search(web, availableName(Kind.webSearch, web))).id());
                    }
                    return input.preferredLanguage() == null ? null : identity.language(actor, input.preferredLanguage());
                });
            }
        }
    }

    private Draft normalize(Kind kind, Draft input) {
        if (input == null || input.type() == null) throw invalid("Provider type is required");
        String type = input.type();
        String endpoint = Objects.toString(input.endpoint(), "").strip();
        String key = Objects.toString(input.apiKey(), "").strip();
        String model = Objects.toString(input.model(), "").strip();
        ProviderSecrets.validate(key);
        if (kind != Kind.language || type.equals("OpenAiCompiable")) ProviderService.validateUrl(endpoint);
        if (model.length() > 255 || model.chars().anyMatch(Character::isISOControl)) throw invalid("Invalid model name");
        return switch (kind) {
            case language -> {
                if (type.equals("OpenAiCompiable")) yield new Draft(type, withoutTrailingSlash(endpoint), key, model);
                if (!LANGUAGE_MODELS.containsKey(type)) throw invalid("Unsupported language provider");
                yield new Draft(type, "", key, model.isEmpty() ? LANGUAGE_MODELS.get(type) : model);
            }
            case embedding -> {
                String base = withoutTrailingSlash(endpoint).replaceFirst("/embeddings$", "");
                String defaultModel = switch (type) {
                    case "openai" -> "text-embedding-3-small";
                    case "qwen" -> "text-embedding-v4";
                    default -> throw invalid("Unsupported embedding provider");
                };
                yield new Draft(type, base, key, model.isEmpty() ? defaultModel : model);
            }
            case image -> new Draft(type, endpoint, key, model);
            case webSearch -> new Draft(type, endpoint, key, type.equals("openai_responses") ? "gpt-5-mini" : "");
        };
    }

    private List<String> listModels(Draft draft) {
        var request = HttpRequest.newBuilder(URI.create(draft.endpoint() + "/models"))
                .header("Authorization", "Bearer " + draft.apiKey()).GET().build();
        try (var http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build()) {
            var pending = http.sendAsync(request, ignored -> new LimitedResponseBody(512 * 1024));
            try {
                var response = pending.get(8, TimeUnit.SECONDS);
                if (response.statusCode() != 200) return List.of();
                var data = json.readTree(response.body()).path("data");
                if (!data.isArray()) return List.of();
                return data.valueStream().map(node -> node.path("id").stringValue("")).filter(id -> !id.isBlank()
                        && id.equals(id.strip()) && id.length() <= 255 && id.chars().noneMatch(Character::isISOControl))
                        .distinct().limit(100).toList();
            } finally {
                if (!pending.isDone()) {
                    pending.cancel(true);
                    http.shutdownNow();
                }
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return List.of();
        } catch (ExecutionException | TimeoutException | RuntimeException ignored) {
            return List.of();
        }
    }

    private static String withoutTrailingSlash(String endpoint) {
        return endpoint.replaceAll("/+$", "");
    }

    private static ProviderDefinition language(Draft draft, String name) {
        return new ProviderDefinition(null, name, draft.type(), true,
                draft.endpoint().isEmpty() ? null : draft.endpoint(), draft.apiKey(), draft.model(),
                null, null, null, null, null);
    }

    private String availableName(Kind kind, Draft draft) {
        String query = switch (kind) {
            case language -> "SELECT EXISTS(SELECT 1 FROM providers WHERE name=?)";
            case image -> "SELECT EXISTS(SELECT 1 FROM image_generation_providers WHERE name=?)";
            case webSearch -> "SELECT EXISTS(SELECT 1 FROM web_search_providers WHERE name=?)";
            case embedding -> throw new IllegalArgumentException("Quick setup requires an empty embedding catalog");
        };
        int limit = kind == Kind.language ? 255 : 100;
        String name = defaultName(draft, limit, "");
        for (int index = 2; Boolean.TRUE.equals(jdbc.queryForObject(query, Boolean.class, name)); index++) {
            name = defaultName(draft, limit, " (" + index + ")");
        }
        return name;
    }

    private static String defaultName(Draft draft, int limit, String number) {
        String vendor = switch (draft.type()) {
            case "OpenAi", "openai", "openai_responses" -> "OpenAI";
            case "OpenAiCompiable" -> "OpenAI Compatible";
            case "Anthropic" -> "Anthropic";
            case "GoogleAiGemini" -> "Google AI Gemini";
            case "Qwen", "qwen" -> "QWen";
            case "deepseek_search" -> "DeepSeek";
            case "bocha_web_search" -> "Bocha";
            default -> throw invalid("Unsupported provider type");
        };
        String model = draft.model().isEmpty() ? "Search" : draft.model();
        String suffix = number + " default";
        int maxModel = limit - vendor.length() - 1 - suffix.length();
        if (model.codePointCount(0, model.length()) > maxModel) {
            model = model.substring(0, model.offsetByCodePoints(0, maxModel)).stripTrailing();
        }
        return vendor + " " + model + suffix;
    }

    private static EmbeddingProviderDefinition embedding(Draft draft) {
        String name = defaultName(draft, 100, "").replaceAll("[^A-Za-z0-9_. -]", "-");
        return new EmbeddingProviderDefinition(name, draft.type(), draft.model(),
                draft.endpoint(), draft.apiKey(), draft.type().equals("qwen") ? 1024 : 1536, null, null, true);
    }

    private static ImageProvider.Input image(Draft draft, String name) {
        return new ImageProvider.Input(name, draft.type(), draft.endpoint(), draft.apiKey(), draft.model(),
                true, true, null, null, null);
    }

    private static WebSearchProvider.Input search(Draft draft, String name) {
        return new WebSearchProvider.Input(name, draft.type(), draft.endpoint(), draft.apiKey(),
                draft.model().isEmpty() ? null : draft.model(), null, null, null);
    }

    private String issueReceipt(UUID actor, Kind kind, Draft draft, long latency) {
        String metadata = Instant.now().plus(RECEIPT_LIFETIME).getEpochSecond() + "." + latency;
        return metadata + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(signature(actor, kind, draft, metadata));
    }

    private Passed checked(UUID actor, Kind kind, Verified verified) {
        if (verified == null || verified.receipt() == null || verified.receipt().length() > 100) throw unverified();
        var draft = normalize(kind, verified.draft());
        var parts = verified.receipt().split("\\.", -1);
        try {
            if (parts.length != 3 || Long.parseLong(parts[0]) <= Instant.now().getEpochSecond()
                    || !MessageDigest.isEqual(Base64.getUrlDecoder().decode(parts[2]),
                            signature(actor, kind, draft, parts[0] + "." + parts[1]))) throw unverified();
            return new Passed(draft, Long.parseLong(parts[1]));
        } catch (IllegalArgumentException malformed) {
            throw unverified();
        }
    }

    private byte[] signature(UUID actor, Kind kind, Draft draft, String metadata) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(receiptKey);
            return mac.doFinal(json.writeValueAsBytes(List.of(actor, kind, draft, metadata)));
        } catch (GeneralSecurityException error) {
            throw new IllegalStateException("Unable to verify quick setup test", error);
        }
    }

    private static void requireSaved(ServiceResult<?> result) {
        if (result instanceof ServiceResult.Failure<?> failure) {
            throw ServiceResponses.exception(failure);
        }
    }

    private static void requireEmptyEmbeddings(boolean hasEmbedding) {
        if (hasEmbedding) throw new ServiceResponseException(HttpStatus.CONFLICT, CONFLICT,
                "An embedding provider is already configured. Manage providers in Settings.");
    }

    private static ServiceResponseException unverified() {
        return new ServiceResponseException(HttpStatus.PRECONDITION_FAILED, INVALID_ARGUMENT,
                "Test all selected providers before saving.");
    }

    private static ServiceResponseException invalid(String message) {
        return new ServiceResponseException(HttpStatus.BAD_REQUEST, INVALID_ARGUMENT, message);
    }
}
