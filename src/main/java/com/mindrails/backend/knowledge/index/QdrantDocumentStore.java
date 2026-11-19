package com.mindrails.backend.knowledge.index;

import com.mindrails.backend.embedding.JsonHttpClient;
import com.mindrails.backend.embedding.QdrantProperties;
import com.mindrails.backend.knowledge.model.KnowledgeIndexProfile;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.embedding.Embedding;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingMatch;
import dev.langchain4j.store.embedding.EmbeddingSearchRequest;
import dev.langchain4j.store.embedding.EmbeddingSearchResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.net.URI;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * A scoped document adapter. The immutable scope is resolved by the search/ingestion services,
 * never by the model.
 */
public final class QdrantDocumentStore implements KnowledgeIndex {
    private final KnowledgeIndexProfile profile;
    private final UUID knowledgeBaseId;
    private final Set<UUID> published;
    private final JsonHttpClient http;
    private final URI base;
    private final Map<String, String> headers;
    private final String vectorName;

    public QdrantDocumentStore(
            KnowledgeIndexProfile profile,
            UUID knowledgeBaseId,
            Set<UUID> published,
            QdrantProperties settings,
            ObjectMapper mapper,
            boolean initialize) {
        this.profile = profile;
        this.knowledgeBaseId = knowledgeBaseId;
        this.published = published == null ? null : Set.copyOf(published);
        vectorName = "knowledge_" + profile.fingerprint();
        try {
            base =
                    new URI(
                            settings.isTls() ? "https" : "http",
                            null,
                            settings.getHost(),
                            settings.getPort(),
                            "/collections/" + profile.collection(),
                            null,
                            null);
        } catch (Exception e) {
            throw KnowledgeException.invalid("Invalid Qdrant address");
        }
        headers =
                settings.getApiKey().isBlank() ? Map.of() : Map.of("api-key", settings.getApiKey());
        http = new JsonHttpClient(mapper, settings.getTimeoutMillis());
        if (initialize) {
            try {
                initialize();
            } catch (RuntimeException e) {
                close();
                throw e;
            }
        }
    }

    private void initialize() {
        var response = request("GET", "", null);
        if (response.status() == 404) {
            request(
                            "PUT",
                            "",
                            Map.of(
                                    "vectors",
                                    Map.of(
                                            vectorName,
                                            Map.of(
                                                    "size",
                                                    profile.dimensions(),
                                                    "distance",
                                                    "Cosine"))))
                    .requireSuccess();
            response = request("GET", "", null);
            for (String field : List.of("knowledgeBaseId", "revisionId", "generation")) {
                request(
                                "PUT",
                                "/index?wait=true",
                                Map.of("field_name", field, "field_schema", "keyword"))
                        .requireSuccess();
            }
        }
        var vector =
                response.requireSuccess()
                        .path("result")
                        .path("config")
                        .path("params")
                        .path("vectors")
                        .path(vectorName);
        if (vector.path("size").asInt() != profile.dimensions()
                || !"Cosine".equals(vector.path("distance").asText())) {
            throw KnowledgeException.conflict(
                    "Knowledge embedding profile mismatch; rebuild the index");
        }
    }

    @Override
    public String add(Embedding embedding) {
        throw new UnsupportedOperationException("Document metadata is required");
    }

    @Override
    public void add(String id, Embedding embedding) {
        throw new UnsupportedOperationException("Document metadata is required");
    }

    @Override
    public String add(Embedding embedding, TextSegment segment) {
        return addAll(List.of(embedding), List.of(segment)).getFirst();
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings) {
        throw new UnsupportedOperationException("Document metadata is required");
    }

    @Override
    public List<String> addAll(List<Embedding> embeddings, List<TextSegment> segments) {
        var ids = segments.stream().map(segment -> segment.metadata().getString("id")).toList();
        addAll(ids, embeddings, segments);
        return ids;
    }

    @Override
    public void addAll(List<String> ids, List<Embedding> embeddings, List<TextSegment> segments) {
        if (ids.size() != embeddings.size() || segments == null || segments.size() != ids.size()) {
            throw new IllegalArgumentException("Mismatched embedding lists");
        }
        var points = new ArrayList<Map<String, Object>>();
        for (int i = 0; i < ids.size(); i++) {
            var segment = segments.get(i);
            if (!knowledgeBaseId
                    .toString()
                    .equals(segment.metadata().getString("knowledgeBaseId"))) {
                throw KnowledgeException.invalid("Invalid document scope");
            }
            points.add(point(ids.get(i), embeddings.get(i), segment));
        }
        if (!points.isEmpty()) {
            request("PUT", "/points?wait=true", Map.of("points", points)).requireSuccess();
        }
    }

    @Override
    public EmbeddingSearchResult<TextSegment> search(EmbeddingSearchRequest query) {
        if (published == null) {
            throw new IllegalStateException("A published revision scope is required for search");
        }
        if (published.isEmpty()) {
            return new EmbeddingSearchResult<>(List.of());
        }
        if (query.filter() != null) {
            throw new IllegalArgumentException("Use the server-resolved document scope");
        }
        var response = request("POST", "/points/query", searchBody(query));
        var matches = new ArrayList<EmbeddingMatch<TextSegment>>();
        for (var point : response.requireSuccess().path("result").path("points")) {
            matches.add(match(point));
        }
        return new EmbeddingSearchResult<>(matches);
    }

    private Map<String, Object> point(String id, Embedding embedding, TextSegment segment) {
        var payload = new LinkedHashMap<String, Object>();
        payload.put("text", segment.text());
        payload.put("metadata", segment.metadata().toMap());
        payload.put("knowledgeBaseId", knowledgeBaseId.toString());
        payload.put("revisionId", segment.metadata().getString("revisionId"));
        payload.put("generation", profile.generation().toString());
        return Map.of(
                "id", id, "vector", Map.of(vectorName, embedding.vector()), "payload", payload);
    }

    private Map<String, Object> searchBody(EmbeddingSearchRequest query) {
        var must = new ArrayList<Map<String, Object>>();
        must.add(condition("knowledgeBaseId", knowledgeBaseId.toString()));
        must.add(condition("generation", profile.generation().toString()));
        must.add(
                Map.of(
                        "key",
                        "revisionId",
                        "match",
                        Map.of("any", published.stream().map(UUID::toString).toList())));
        var body = new LinkedHashMap<String, Object>();
        body.put("query", query.queryEmbedding().vector());
        body.put("using", vectorName);
        body.put("filter", Map.of("must", must));
        body.put("limit", query.maxResults());
        body.put("score_threshold", query.minScore());
        body.put("with_payload", true);
        return body;
    }

    private EmbeddingMatch<TextSegment> match(JsonNode point) {
        var payload = point.path("payload");
        var values = new HashMap<String, Object>();
        for (var entry : payload.path("metadata").properties()) {
            var value = entry.getValue();
            Object decoded = value.isNumber() ? value.numberValue() : value.asText();
            values.put(entry.getKey(), decoded);
        }
        var metadata = Metadata.from(values);
        double score = point.path("score").asDouble();
        metadata.put("score", score);
        if (!knowledgeBaseId.toString().equals(metadata.getString("knowledgeBaseId"))
                || !published.contains(UUID.fromString(metadata.getString("revisionId")))) {
            throw new IllegalStateException("Invalid Qdrant result scope");
        }
        return new EmbeddingMatch<>(
                score,
                point.path("id").asText(),
                null,
                TextSegment.from(payload.path("text").asText(), metadata));
    }

    public void removeRevision(UUID revision) {
        var scope =
                List.of(
                        condition("knowledgeBaseId", knowledgeBaseId.toString()),
                        condition("revisionId", revision.toString()));
        var result =
                request(
                        "POST",
                        "/points/delete?wait=true",
                        Map.of("filter", Map.of("must", scope)));
        if (result.status() != 404) {
            result.requireSuccess();
        }
    }

    public void drop() {
        var result = request("DELETE", "", null);
        if (result.status() != 404) {
            result.requireSuccess();
        }
    }

    private Map<String, Object> condition(String key, Object value) {
        return Map.of("key", key, "match", Map.of("value", value));
    }

    private JsonHttpClient.Response request(String method, String suffix, Object body) {
        return http.request(method, URI.create(base + suffix), body, headers);
    }

    @Override
    public void close() {
        http.close();
    }
}
