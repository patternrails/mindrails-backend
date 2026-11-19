package com.mindrails.backend.websearch;

import com.mindrails.backend.common.ServiceResponseException;
import com.mindrails.backend.provider.ProviderConnectionTest;
import com.mindrails.backend.provider.ProviderSecrets;
import com.mindrails.backend.tool.ToolFailure;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;

import static com.mindrails.backend.common.ServiceResult.ErrorCode.*;

@Service
public final class WebSearchProviderService {
    private static final Set<String> TYPES = Set.of(WebSearchProvider.OPENAI, WebSearchProvider.DEEPSEEK, WebSearchProvider.BOCHA);
    private static final Set<String> LOOPBACK = Set.of("localhost", "127.0.0.1", "[::1]");
    private final WebSearchProviderRegistry registry;
    private final WebSearchClient client;

    public WebSearchProviderService(WebSearchProviderRegistry registry, WebSearchClient client) {
        this.registry = registry;
        this.client = client;
    }

    public List<WebSearchProvider> list(int page, int size) {
        if (page < 0 || size < 1 || size > 100) throw invalid("Invalid pagination");
        return registry.list(page, size);
    }

    public WebSearchProvider get(UUID id) {
        return registry.find(id).orElseThrow(WebSearchProviderService::notFound);
    }

    public WebSearchProvider.TestResult test(UUID id) {
        var connection = registry.connection(id).orElseThrow(WebSearchProviderService::notFound);
        return test(connection);
    }

    public ProviderConnectionTest.Result probe(WebSearchProvider.Input input) {
        var config = validate(input, true);
        var provider = new WebSearchProvider(UUID.randomUUID(), config.name(), config.type(), config.endpoint(),
                config.modelName(), config.options(), config.timeoutMillis(), false, 0, null, null);
        var result = test(new WebSearchProviderRegistry.Connection(provider, config.apiKey()));
        return new ProviderConnectionTest.Result(result.connected(), result.latencyMs(), result.message());
    }

    private WebSearchProvider.TestResult test(WebSearchProviderRegistry.Connection connection) {
        long start = System.nanoTime();
        boolean connected = false;
        String message;
        try {
            var result = client.search(connection, "NASA official website", "noLimit", 1, false, null, null, null);
            connected = true;
            message = result.pages().isEmpty() ? "Search API responded successfully with no pages"
                    : "Search API returned a valid page";
        } catch (ToolFailure failure) {
            message = failure.getMessage();
        } catch (CancellationException cancelled) {
            message = "Web search connection test interrupted";
        }
        return new WebSearchProvider.TestResult(connection.provider().id(), connection.provider().version(), connected,
                TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start), message);
    }

    public WebSearchProvider save(UUID id, WebSearchProvider.Input input) {
        var config = validate(input, id == null);
        try {
            return registry.write(() -> {
                if (id == null) {
                    UUID created = UUID.randomUUID();
                    registry.insert(created, config);
                    return get(created);
                }
                get(id);
                if (!registry.update(id, config)) throw conflict("Web search provider was changed. Refresh and try again.");
                return get(id);
            });
        } catch (DuplicateKeyException duplicate) {
            throw conflict("A web search provider with that name already exists");
        }
    }

    public WebSearchProvider activate(UUID id) {
        return registry.write(() -> {
            get(id);
            registry.activate(id);
            return get(id);
        });
    }

    public void delete(UUID id) {
        registry.write(() -> {
            registry.delete(get(id));
            return null;
        });
    }

    private static WebSearchProvider.Input validate(WebSearchProvider.Input input, boolean creating) {
        if (input == null || input.name() == null || input.name().isBlank() || input.name().length() > 100
                || input.name().chars().anyMatch(Character::isISOControl)
                || input.type() == null || !TYPES.contains(input.type())) {
            throw invalid("A name and supported web search provider type are required");
        }
        String model = input.modelName() == null || input.modelName().isBlank() ? null : input.modelName().strip();
        if (WebSearchProvider.OPENAI.equals(input.type()) ? model == null || model.length() > 255 : model != null) {
            throw invalid("A model name is required for OpenAI and must be empty for other types");
        }
        int timeout = input.timeoutMillis() == null ? 15000 : input.timeoutMillis();
        if (timeout < 1 || timeout > 120000) throw invalid("Timeout must be between 1 and 120000 milliseconds");
        if (!creating && (input.version() == null || input.version() < 0)) throw invalid("A version is required");
        String apiKey = input.apiKey() == null || input.apiKey().isBlank() ? null : input.apiKey();
        try {
            if (creating || apiKey != null) ProviderSecrets.validate(apiKey);
        } catch (IllegalArgumentException invalidKey) {
            throw invalid(invalidKey.getMessage());
        }
        var options = input.options() == null ? Map.<String, Object>of() : input.options();
        options.forEach((key, value) -> validateOption(input.type(), key, value));
        return new WebSearchProvider.Input(input.name().strip(), input.type(), endpoint(input.endpoint()),
                apiKey, model, Map.copyOf(options), timeout, input.version());
    }

    private static void validateOption(String type, String key, Object value) {
        boolean valid = switch (type) {
            case WebSearchProvider.OPENAI -> key.equals("search_context_size")
                    && value instanceof String size && Set.of("low", "medium", "high").contains(size);
            case WebSearchProvider.DEEPSEEK -> key.equals("need_content") && value instanceof Boolean;
            case WebSearchProvider.BOCHA -> switch (key) {
                case "summary" -> value instanceof Boolean;
                case "freshness" -> value instanceof String freshness && WebSearchQuery.validFreshness(freshness);
                default -> false;
            };
            default -> false;
        };
        if (!valid) throw invalid("Invalid web search provider options");
    }

    private static String endpoint(String value) {
        if (value != null && value.length() <= 2048) {
            try {
                URI uri = URI.create(value.strip());
                if (uri.getHost() != null && uri.getUserInfo() == null && uri.getFragment() == null
                        && uri.getRawQuery() == null && ("https".equals(uri.getScheme())
                        || "http".equals(uri.getScheme()) && LOOPBACK.contains(uri.getHost()))) {
                    return value.strip();
                }
            } catch (IllegalArgumentException ignored) {
                // Return the same validation error for malformed and unsupported endpoints.
            }
        }
        throw invalid("A full HTTPS endpoint is required (HTTP is allowed for local testing)");
    }

    private static ServiceResponseException invalid(String message) {
        return new ServiceResponseException(HttpStatus.BAD_REQUEST, INVALID_ARGUMENT, message);
    }

    private static ServiceResponseException notFound() {
        return new ServiceResponseException(HttpStatus.NOT_FOUND, NOT_FOUND, "Web search provider not found");
    }

    private static ServiceResponseException conflict(String message) {
        return new ServiceResponseException(HttpStatus.CONFLICT, CONFLICT, message);
    }
}
