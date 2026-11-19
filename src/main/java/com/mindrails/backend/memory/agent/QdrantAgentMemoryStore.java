package com.mindrails.backend.memory.agent;

import com.mindrails.backend.embedding.EmbeddingProvider;
import com.mindrails.backend.embedding.JsonHttpClient;
import com.mindrails.backend.embedding.QdrantProperties;
import java.net.URI;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DuplicateKeyException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Original entries and vectors live together. Single application writer; no distributed SQL/CAS claims. */
public final class QdrantAgentMemoryStore implements AgentMemoryStore, AutoCloseable {
    private EmbeddingProvider embedding;
    private final Supplier<Optional<String>> defaultName;
    private final Function<String, EmbeddingProvider> resolveEmbedding;
    private final ObjectMapper mapper;
    private final JsonHttpClient http;
    private final URI base;
    private final Map<String, String> headers;
    private String vectorName;
    private volatile boolean initialized;

    public QdrantAgentMemoryStore(QdrantProperties settings, String collection, EmbeddingProvider embedding, ObjectMapper mapper) {
        this(settings, collection, Optional::empty, null, mapper);
        try { initialize(embedding); }
        catch (RuntimeException failure) { http.close(); throw failure; }
    }

    QdrantAgentMemoryStore(QdrantProperties settings, String collection, Supplier<Optional<String>> defaultName,
            Function<String, EmbeddingProvider> resolveEmbedding, ObjectMapper mapper) {
        this.defaultName = defaultName;
        this.resolveEmbedding = resolveEmbedding;
        this.mapper = mapper;
        if (!settings.getHost().matches("[A-Za-z0-9.:-]+")) throw new IllegalArgumentException("Invalid Qdrant host");
        if (!collection.matches("[A-Za-z0-9_-]{1,100}")) throw new IllegalArgumentException("Invalid Qdrant collection");
        if (!settings.getApiKey().isEmpty() && !settings.getApiKey().matches("[\\x21-\\x7E]+"))
            throw new IllegalArgumentException("Invalid Qdrant API key format");
        try { base = new URI(settings.isTls() ? "https" : "http", null, settings.getHost(), settings.getPort(),
                "/collections/" + collection, null, null); }
        catch (java.net.URISyntaxException invalid) { throw new IllegalArgumentException("Invalid Qdrant address"); }
        headers = settings.getApiKey().isBlank() ? Map.of() : Map.of("api-key", settings.getApiKey());
        http = new JsonHttpClient(mapper, settings.getTimeoutMillis());
    }

    @Override public boolean available() {
        return initialized || defaultName.get().isPresent();
    }

    private synchronized void initializeIfNeeded() {
        if (initialized) return;
        var name = defaultName.get().orElseThrow(() -> new IllegalStateException("Default embedding provider is not configured"));
        initialize(resolveEmbedding.apply(name));
    }

    private void initialize(EmbeddingProvider provider) {
        String vector = "memory_" + provider.fingerprint();
        var response = send("GET", "", null);
        if (response.status() == 404) {
            // The named vector encodes the complete embedding profile, not merely its dimension.
            var created = send("PUT", "", Map.of("vectors", Map.of(vector,
                    Map.of("size", provider.dimensions(), "distance", "Cosine"))));
            if (created.status() < 200 || created.status() >= 300) {
                // Another startup may have created the collection. Always validate what actually exists.
                send("GET", "", null).requireSuccess();
            }
            response = send("GET", "", null);
        }
        var vectors = response.requireSuccess().path("result").path("config").path("params").path("vectors");
        if (!vectors.isObject() || vectors.size() != 1 || !vectors.has(vector)
                || vectors.path(vector).path("size").asInt() != provider.dimensions()
                || !"Cosine".equals(vectors.path(vector).path("distance").asText()))
            throw new IllegalStateException("Qdrant embedding profile mismatch; rebuild into a new collection");
        for (String field : List.of("memory_id", "agent_id", "entry_key"))
            send("PUT", "/index?wait=true", Map.of("field_name", field, "field_schema", "keyword")).requireSuccess();
        embedding = provider;
        vectorName = vector;
        initialized = true;
    }

    @Override public List<AgentMemoryEntry> entries(UUID memoryId) {
        return scroll(filter(memoryId), false).stream().map(this::entry)
                .sorted(Comparator.comparing(AgentMemoryEntry::createdAt).thenComparing(e -> e.id().toString())).toList();
    }

    @Override public Optional<AgentMemoryEntry> find(UUID memoryId, UUID id) {
        var point = point(id);
        return point.map(this::entry).filter(entry -> entry.memoryId().equals(memoryId));
    }

    @Override public Optional<AgentMemoryEntry> findByKey(UUID memoryId, String key) {
        return scroll(filter(memoryId, condition("entry_key", key)), false).stream().map(this::entry).findFirst();
    }

    @Override public PreparedWrite prepare(AgentMemoryEntry entry) {
        // Metadata-only edits reuse the vector; preparation never holds the Service collection guard.
        var current = point(entry.id());
        if (current.isPresent() && entry.memoryId().equals(entry(current.get()).memoryId())
                && embeddingText(entry).equals(embeddingText(entry(current.get())))) {
            return new PreparedWrite(entry, current.get().path("vector").path(vectorName));
        }
        return new PreparedWrite(entry, embedding.embed(embeddingText(entry)));
    }

    public static String embeddingText(AgentMemoryEntry entry) {
        return entry.type() + "\n" + entry.key() + "\n" + String.join(", ", entry.tags()) + "\n" + entry.content();
    }

    @Override public synchronized void insert(AgentMemoryEntry entry) { insertPrepared(prepare(entry)); }
    @Override public synchronized void insertPrepared(PreparedWrite write) {
        if (point(write.entry().id()).isPresent()) throw new DuplicateKeyException("Memory ID already exists");
        checkKey(write.entry());
        upsert(List.of(point(write)));
    }

    @Override public synchronized boolean update(AgentMemoryEntry entry, long expectedVersion) {
        return updatePrepared(prepare(entry), expectedVersion);
    }
    @Override public synchronized boolean updatePrepared(PreparedWrite write, long expectedVersion) {
        var current = find(write.entry().memoryId(), write.entry().id()).orElse(null);
        if (current == null || current.version() != expectedVersion) return false;
        checkKey(write.entry());
        upsert(List.of(point(write)));
        return true;
    }

    @Override public synchronized boolean delete(UUID memoryId, UUID id, long expectedVersion) {
        var current = find(memoryId, id).orElse(null);
        if (current == null || current.version() != expectedVersion) return false;
        request("POST", "/points/delete?wait=true", Map.of("filter", filter(memoryId, condition("entry_id", id.toString())))).requireSuccess();
        return true;
    }

    @Override public synchronized void deleteCollection(UUID memoryId) {
        request("POST", "/points/delete?wait=true", Map.of("filter", filter(memoryId))).requireSuccess();
    }

    @Override public Runnable deletionRollback(UUID memoryId) {
        var backup = scroll(filter(memoryId), true).stream().map(point -> Map.<String, Object>of(
                "id", point.path("id").asText(), "payload", point.path("payload"), "vector", point.path("vector"))).toList();
        return () -> { deleteCollection(memoryId); upsert(backup); };
    }

    @Override public List<AgentMemoryEntry> retrieve(UUID agentId, UUID memoryId, String query, boolean automatic,
            AgentMemoryProperties properties) {
        var scope = filter(memoryId, condition("agent_id", agentId.toString()));
        var count = request("POST", "/points/count", Map.of("filter", scope, "exact", true)).requireSuccess();
        if (count.path("result").path("count").asLong() == 0) return List.of();
        var selected = new LinkedHashMap<UUID, AgentMemoryEntry>();
        if (automatic) {
            scroll(filter(memoryId, condition("agent_id", agentId.toString()), condition("pinned", true)), false).stream()
                    .map(this::entry).sorted(Comparator.comparingInt(AgentMemoryEntry::priority).reversed()
                            .thenComparing(AgentMemoryEntry::updatedAt, Comparator.reverseOrder()))
                    .forEach(entry -> selected.put(entry.id(), entry));
        }
        var vector = embedding.embed(query);
        var hits = request("POST", "/points/query", Map.of("query", vector, "using", vectorName, "filter", scope,
                "limit", Math.min(1000, properties.getRetrieval().getMaxResults() * 4), "with_payload", true,
                "score_threshold", properties.getRetrieval().getMinScore())).requireSuccess().path("result").path("points");
        if (!hits.isArray()) throw new IllegalStateException("Invalid Qdrant search response");
        for (var hit : hits) {
            var entry = entry(hit);
            if (!entry.agentId().equals(agentId) || !entry.memoryId().equals(memoryId))
                throw new IllegalStateException("Qdrant search returned an invalid memory scope");
            selected.putIfAbsent(entry.id(), entry);
        }
        return new AgentMemoryRetrieval(properties).budget(new ArrayList<>(selected.values()));
    }

    private void checkKey(AgentMemoryEntry entry) {
        var same = findByKey(entry.memoryId(), entry.key()).orElse(null);
        if (same != null && !same.id().equals(entry.id())) throw new DuplicateKeyException("Memory key already exists");
    }

    private Optional<JsonNode> point(UUID id) {
        var response = request("POST", "/points", Map.of("ids", List.of(id.toString()), "with_payload", true, "with_vector", true));
        var points = response.requireSuccess().path("result");
        if (!points.isArray()) throw new IllegalStateException("Invalid Qdrant point response");
        return points.isEmpty() ? Optional.empty() : Optional.of(points.get(0));
    }

    private Map<String, Object> point(PreparedWrite write) {
        var entry = write.entry();
        if (write.state() == null) throw new IllegalStateException("Qdrant write requires an embedding");
        return Map.of("id", entry.id().toString(), "vector", Map.of(vectorName, write.state()), "payload", Map.of(
                "entry", entry, "entry_id", entry.id().toString(), "memory_id", entry.memoryId().toString(),
                "agent_id", entry.agentId().toString(), "entry_key", entry.key(), "pinned", entry.pinned()));
    }

    private AgentMemoryEntry entry(JsonNode point) {
        try {
            var entry = mapper.treeToValue(point.path("payload").path("entry"), AgentMemoryEntry.class);
            if (entry == null || !entry.id().toString().equals(point.path("id").asText())
                    || !entry.memoryId().toString().equals(point.path("payload").path("memory_id").asText())
                    || !entry.agentId().toString().equals(point.path("payload").path("agent_id").asText()))
                throw new IllegalStateException("Invalid stored memory scope");
            return entry;
        } catch (RuntimeException invalid) { throw new IllegalStateException("Invalid Qdrant memory payload"); }
    }

    private List<JsonNode> scroll(Map<String, Object> filter, boolean vectors) {
        var points = new ArrayList<JsonNode>();
        JsonNode offset = null;
        do {
            var body = new LinkedHashMap<String, Object>(Map.of("filter", filter, "limit", 256,
                    "with_payload", true, "with_vector", vectors));
            if (offset != null && !offset.isNull()) body.put("offset", offset);
            var result = request("POST", "/points/scroll", body).requireSuccess().path("result");
            if (!result.path("points").isArray()) throw new IllegalStateException("Invalid Qdrant scroll response");
            result.path("points").forEach(points::add);
            if (points.size() > 10000) throw new IllegalStateException("Qdrant memory collection exceeds supported capacity");
            offset = result.path("next_page_offset");
        } while (!offset.isMissingNode() && !offset.isNull());
        return List.copyOf(points);
    }

    private void upsert(List<Map<String, Object>> points) {
        for (int start = 0; start < points.size(); start += 64)
            request("PUT", "/points?wait=true", Map.of("points", points.subList(start, Math.min(start + 64, points.size())))).requireSuccess();
    }

    private static Map<String, Object> condition(String key, Object value) { return Map.of("key", key, "match", Map.of("value", value)); }
    @SafeVarargs private static Map<String, Object> filter(UUID memoryId, Map<String, Object>... extra) {
        var must = new ArrayList<Map<String, Object>>();
        must.add(condition("memory_id", memoryId.toString()));
        must.addAll(List.of(extra));
        return Map.of("must", must);
    }

    private JsonHttpClient.Response request(String method, String suffix, Object body) {
        if (!initialized) initializeIfNeeded();
        return send(method, suffix, body);
    }

    private JsonHttpClient.Response send(String method, String suffix, Object body) {
        return http.request(method, URI.create(base.toString() + suffix), body, headers);
    }

    @Override public void close() { http.close(); }
}
