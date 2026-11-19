package com.mindrails.backend.embedding;

import com.mindrails.backend.provider.ProviderSecrets;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;

/** MySQL-backed catalog; build runtime clients for the selected providers on demand. */
public final class EmbeddingProviderRegistry implements AutoCloseable {
    private final Map<String, EmbeddingProviderDefinition> definitions = new HashMap<>();
    private final Map<String, RemoteProvider> clients = new HashMap<>();
    private final Map<String, EmbeddingProvider> handles = new HashMap<>();
    private final java.util.List<RemoteProvider> retired = new java.util.ArrayList<>();
    private final JdbcTemplate jdbc;
    private final Environment environment;
    private final ObjectMapper mapper;
    private final ProviderSecrets secrets;

    public record Info(
            String name,
            String type,
            String modelName,
            int dimensions,
            String baseUrl,
            boolean hasApiKey,
            Integer timeoutMillis,
            Integer maxRetries,
            boolean defaultProvider) {}

    public EmbeddingProviderRegistry(ObjectMapper mapper, Environment env, JdbcTemplate jdbc) {
        this.mapper = mapper;
        this.secrets = new ProviderSecrets(env);
        this.environment = env;
        this.jdbc = jdbc;
        var rows = jdbc.query(
                "SELECT * FROM embedding_providers ORDER BY name",
                (r, n) -> new EmbeddingProviderDefinition(
                        r.getString("name"),
                        r.getString("type"),
                        r.getString("model_name"),
                        r.getString("base_url"),
                        secrets.decrypt(r.getString("api_key")),
                        r.getInt("dimensions"),
                        (Integer) r.getObject("timeout_millis"),
                        (Integer) r.getObject("max_retries"),
                        r.getBoolean("is_default")));
        for (var definition : rows) {
            validate(definition);
            definitions.put(definition.name(), definition);
        }
        validateDefault(definitions);
    }

    private static void validateDefault(Map<String, EmbeddingProviderDefinition> entries) {
        if (entries.values().stream().filter(d -> Boolean.TRUE.equals(d.defaultProvider())).count() > 1)
            throw new IllegalArgumentException("Only one default embedding provider is allowed");
    }

    public synchronized java.util.Optional<String> defaultProviderName() {
        return definitions.values().stream().filter(d -> Boolean.TRUE.equals(d.defaultProvider()))
                .map(EmbeddingProviderDefinition::name).findFirst();
    }

    public String requireDefaultProviderName() {
        return defaultProviderName().orElseThrow(() -> new IllegalStateException(
                "Default embedding provider is not configured; initialize the system embedding configuration"));
    }

    public String endpoint(EmbeddingProviderDefinition definition) {
        return environment.resolveRequiredPlaceholders(definition.baseUrl());
    }

    public void check(EmbeddingProviderDefinition definition) {
        validate(definition);
        com.mindrails.backend.provider.ProviderService.validateUrl(endpoint(definition));
    }

    public synchronized java.util.Optional<EmbeddingProviderDefinition> definition(String name) {
        return java.util.Optional.ofNullable(definitions.get(name));
    }

    public static void validate(EmbeddingProviderDefinition d) {
        if (d == null
                || d.name() == null
                || !d.name().matches("[A-Za-z0-9_. -]{1,100}")
                || d.name().isBlank() || !d.name().equals(d.name().strip())
                || d.name().equals(".") || d.name().equals("..")
                || d.type() == null
                || !List.of("qwen", "openai").contains(d.type())
                || d.modelName() == null
                || d.modelName().isBlank()
                || d.modelName().length() > 255
                || d.dimensions() < 1
                || d.dimensions() > 8192)
            throw new IllegalArgumentException("Invalid embedding name, type, model or dimensions");
        if (d.baseUrl() == null) throw new IllegalArgumentException("Endpoint is required");
        if (!d.baseUrl().contains("${"))
            com.mindrails.backend.provider.ProviderService.validateUrl(d.baseUrl());
        if (d.apiKey() != null && !d.apiKey().isBlank()) ProviderSecrets.validate(d.apiKey());
        if (d.timeoutMillis() != null && (d.timeoutMillis() < 100 || d.timeoutMillis() > 60000)
                || d.maxRetries() != null && (d.maxRetries() < 0 || d.maxRetries() > 3))
            throw new IllegalArgumentException("Invalid timeout or retries");
    }

    public synchronized Info save(EmbeddingProviderDefinition d) {
        check(d);
        var next = new HashMap<>(definitions);
        next.put(d.name(), d);
        validateDefault(next);
        persist(next);
        publishAfterCommit(next, d.name());
        return info(d);
    }

    public synchronized void delete(String name) {
        var next = new HashMap<>(definitions);
        next.remove(name);
        validateDefault(next);
        persist(next);
        publishAfterCommit(next, name);
    }

    private void publishAfterCommit(Map<String, EmbeddingProviderDefinition> next, String name) {
        if (TransactionSynchronizationManager.isActualTransactionActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() { publish(next, name); }
            });
        } else {
            publish(next, name);
        }
    }

    private synchronized void publish(Map<String, EmbeddingProviderDefinition> next, String name) {
        definitions.clear();
        definitions.putAll(next);
        var previous = clients.remove(name);
        if (previous != null) retired.add(previous);
    }

    private void persist(Map<String, EmbeddingProviderDefinition> next) {
        new TransactionTemplate(
                        new DataSourceTransactionManager(
                                java.util.Objects.requireNonNull(jdbc.getDataSource())))
                .executeWithoutResult(
                        status -> {
                            jdbc.queryForList(
                                    "SELECT name FROM embedding_providers FOR UPDATE",
                                    String.class);
                            for (var name : definitions.keySet())
                                if (!next.containsKey(name))
                                    jdbc.update(
                                            "DELETE FROM embedding_providers WHERE name=?",
                                            name);
                            for (var d : next.values())
                                if (!d.equals(definitions.get(d.name())))
                                    jdbc.update(
                                            "INSERT INTO"
                                                + " embedding_providers(name,type,model_name,base_url,api_key,dimensions,timeout_millis,max_retries,is_default)"
                                                + " VALUES(?,?,?,?,?,?,?,?,?) ON DUPLICATE KEY"
                                                + " UPDATE"
                                                + " type=VALUES(type),model_name=VALUES(model_name),base_url=VALUES(base_url),api_key=VALUES(api_key),dimensions=VALUES(dimensions),timeout_millis=VALUES(timeout_millis),max_retries=VALUES(max_retries),is_default=VALUES(is_default)",
                                            d.name(),
                                            d.type(),
                                            d.modelName(),
                                            d.baseUrl(),
                                            secrets.encrypt(d.apiKey()),
                                            d.dimensions(),
                                            d.timeoutMillis(),
                                            d.maxRetries(),
                                            Boolean.TRUE.equals(d.defaultProvider()));
                        });
    }

    public synchronized List<Info> list() {
        return definitions.values().stream()
                .map(this::info)
                .sorted(java.util.Comparator.comparing(Info::defaultProvider).reversed().thenComparing(Info::name))
                .toList();
    }

    private Info info(EmbeddingProviderDefinition d) {
        return new Info(d.name(), d.type(), d.modelName(), d.dimensions(), endpoint(d),
                d.apiKey() != null && !d.apiKey().isBlank(), d.timeoutMillis(), d.maxRetries(),
                Boolean.TRUE.equals(d.defaultProvider()));
    }

    void probe(EmbeddingProviderDefinition definition) {
        check(definition);
        var provider = build(definition);
        try (var ignored = provider.http()) {
            provider.embed("Connection test");
        }
    }

    private synchronized RemoteProvider client(String name) {
        var definition = definitions.get(name);
        if (definition == null)
            throw new IllegalArgumentException("Embedding provider is not configured: " + name);
        return clients.computeIfAbsent(name, ignored -> build(definition));
    }

    public synchronized EmbeddingProvider require(String name) {
        client(name);
        return handles.computeIfAbsent(
                name,
                ignored ->
                        new EmbeddingProvider() {
                            public List<Double> embed(String text) {
                                return client(name).embed(text);
                            }

                            public List<List<Double>> embedAll(List<String> texts) {
                                return client(name).embedAll(texts);
                            }

                            public int dimensions() {
                                return client(name).dimensions();
                            }

                            public String modelName() {
                                return client(name).modelName();
                            }

                            public String fingerprint() {
                                return client(name).fingerprint();
                            }

                            public String toString() {
                                return "EmbeddingProvider[" + name + "]";
                            }
                        });
    }

    private RemoteProvider build(EmbeddingProviderDefinition definition) {
        if (!List.of("qwen", "openai").contains(definition.type())
                || definition.modelName() == null
                || definition.modelName().isBlank()
                || definition.dimensions() < 1
                || definition.dimensions() > 8192)
            throw new IllegalArgumentException(
                    "Invalid embedding provider model, type or dimensions");
        String key = definition.apiKey();
        String base = environment.resolveRequiredPlaceholders(definition.baseUrl());
        URI uri;
        try {
            uri = URI.create(base.replaceAll("/+$", "") + "/embeddings");
        } catch (RuntimeException invalid) {
            throw new IllegalArgumentException("Invalid embedding baseUrl");
        }
        if (!("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                || uri.getHost() == null
                || uri.getUserInfo() != null
                || uri.getQuery() != null
                || uri.getFragment() != null)
            throw new IllegalArgumentException(
                    "Embedding baseUrl must be an HTTP(S) URL without credentials or query"
                            + " parameters");
        int timeout = definition.timeoutMillis() == null ? 10000 : definition.timeoutMillis();
        int retries = definition.maxRetries() == null ? 1 : definition.maxRetries();
        if (timeout < 100 || timeout > 60000 || retries < 0 || retries > 3)
            throw new IllegalArgumentException("Invalid embedding timeout or retry count");
        String fingerprint =
                fingerprint(
                        definition.type()
                                + "\n"
                                + definition.modelName()
                                + "\n"
                                + definition.dimensions()
                                + "\n"
                                + uri
                                + "\nagent-memory-entry-v1");
        return new RemoteProvider(
                definition, key, uri, fingerprint, retries, new JsonHttpClient(mapper, timeout));
    }

    private static String fingerprint(String value) {
        try {
            return HexFormat.of()
                    .formatHex(
                            MessageDigest.getInstance("SHA-256")
                                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private record RemoteProvider(
            EmbeddingProviderDefinition definition,
            String key,
            URI uri,
            String fingerprint,
            int retries,
            JsonHttpClient http)
            implements EmbeddingProvider {
        @Override
        public String modelName() {
            return definition.modelName();
        }

        @Override
        public int dimensions() {
            return definition.dimensions();
        }

        @Override
        public List<Double> embed(String text) {
            return embedAll(List.of(text)).getFirst();
        }

        @Override
        public List<List<Double>> embedAll(List<String> texts) {
            var output = new java.util.ArrayList<List<Double>>();
            for (int start = 0; start < texts.size(); start += 10)
                output.addAll(batch(texts.subList(start, Math.min(start + 10, texts.size()))));
            return List.copyOf(output);
        }

        private List<List<Double>> batch(List<String> texts) {
            ProviderSecrets.validate(key);
            if (texts.stream().anyMatch(text -> text == null || text.isBlank()))
                throw new IllegalArgumentException("Embedding input must not be blank");
            JsonHttpClient.Response response = null;
            for (int attempt = 0; attempt <= retries; attempt++) {
                response =
                        http.request(
                                "POST",
                                uri,
                                Map.of(
                                        "model",
                                        definition.modelName(),
                                        "input",
                                        texts,
                                        "dimensions",
                                        dimensions(),
                                        "encoding_format",
                                        "float"),
                                Map.of("Authorization", "Bearer " + key));
                if (response.status() != 429 && response.status() < 500) break;
                if (attempt < retries)
                    try {
                        Thread.sleep(100L * (attempt + 1));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        JsonHttpClient.checkActive();
                    }
            }
            var data = response.requireSuccess().path("data");
            if (!data.isArray() || data.size() != texts.size())
                throw new IllegalStateException("Invalid embedding response data");
            var ordered = new java.util.TreeMap<Integer, List<Double>>();
            int fallback = 0;
            for (var item : data) {
                int index = item.has("index") ? item.path("index").asInt(-1) : fallback;
                fallback++;
                var vector = item.path("embedding");
                if (!vector.isArray() || vector.size() != dimensions())
                    throw new IllegalStateException("Embedding dimension mismatch");
                var values = new java.util.ArrayList<Double>();
                double norm = 0;
                for (var component : vector) {
                    double value = component.asDouble(Double.NaN);
                    if (!component.isNumber() || !Double.isFinite(value))
                        throw new IllegalStateException("Invalid embedding vector");
                    values.add(value);
                    norm += value * value;
                }
                if (norm == 0 || !Double.isFinite(norm))
                    throw new IllegalStateException("Invalid embedding vector norm");
                if (index < 0
                        || index >= texts.size()
                        || ordered.put(index, List.copyOf(values)) != null)
                    throw new IllegalStateException("Invalid embedding response order");
            }
            return List.copyOf(ordered.values());
        }

        @Override
        public String toString() {
            return "EmbeddingProvider[" + definition.name() + "]";
        }
    }

    @Override
    public synchronized void close() {
        clients.values().forEach(client -> client.http().close());
        clients.clear();
        retired.forEach(client -> client.http().close());
        retired.clear();
        handles.clear();
    }
}
