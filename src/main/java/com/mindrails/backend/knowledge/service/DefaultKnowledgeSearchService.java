package com.mindrails.backend.knowledge.service;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.index.KnowledgeIndexFactory;
import com.mindrails.backend.knowledge.index.ProviderEmbeddingModel;
import com.mindrails.backend.knowledge.model.KnowledgeBaseState;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentState;
import com.mindrails.backend.knowledge.model.KnowledgeSearchHit;
import com.mindrails.backend.knowledge.model.KnowledgeSearchScope;
import com.mindrails.backend.knowledge.service.internal.KnowledgeAccess;

import dev.langchain4j.rag.content.retriever.EmbeddingStoreContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

public final class DefaultKnowledgeSearchService implements KnowledgeSearchService {
    private final KnowledgeAccess access;
    private final KnowledgeProperties properties;
    private final KnowledgeIndexFactory indexes;

    public DefaultKnowledgeSearchService(
            KnowledgeAccess access, KnowledgeProperties properties, KnowledgeIndexFactory indexes) {
        this.access = access;
        this.properties = properties;
        this.indexes = indexes;
    }

    @Override
    public ServiceResult<List<KnowledgeSearchHit>> search(UUID knowledgeBaseId, String query) {
        return KnowledgeResults.attempt(() -> executeSearch(knowledgeBaseId, query, null));
    }

    @Override
    public ServiceResult<List<KnowledgeSearchHit>> searchVersions(
            UUID kb, String query, java.util.Set<UUID> versions) {
        return KnowledgeResults.attempt(
                () -> executeSearch(kb, query, java.util.Set.copyOf(versions)));
    }

    private List<KnowledgeSearchHit> executeSearch(
            UUID knowledgeBaseId, String query, java.util.Set<UUID> allowed) {
        if (query == null || query.isBlank() || query.length() > 10000) {
            throw KnowledgeException.invalid("Query must contain 1..10000 characters");
        }
        var base = access.getKnowledgeBase(knowledgeBaseId);
        if (base.getState() == KnowledgeBaseState.DELETING) {
            throw KnowledgeException.missing();
        }
        var docs =
                access.listDocuments(knowledgeBaseId).stream()
                        .filter(
                                doc ->
                                        doc.getActiveVersionId() != null
                                                && (doc.getState()
                                                        != KnowledgeDocumentState.DELETING))
                        .filter(
                                doc ->
                                        allowed == null
                                                || allowed.contains(doc.getActiveVersionId()))
                        .toList();
        var published =
                docs.stream()
                        .map(KnowledgeDocument::getActiveVersionId)
                        .collect(Collectors.toSet());
        if (published.isEmpty()) {
            return List.of();
        }
        var provider = indexes.provider(base.getProfile());
        try (var index =
                indexes.forSearch(
                        new KnowledgeSearchScope(knowledgeBaseId, base.getProfile(), published))) {
            var retriever =
                    EmbeddingStoreContentRetriever.builder()
                            .embeddingModel(new ProviderEmbeddingModel(provider))
                            .embeddingStore(index)
                            .maxResults(properties.getMaxResults())
                            .minScore(allowed == null ? properties.getMinScore() : 0.0)
                            .build();
            var contents = retriever.retrieve(Query.from(query));
            var hits = new ArrayList<KnowledgeSearchHit>();
            int chars = 0;
            for (var content : contents) {
                var segment = content.textSegment();
                var meta = segment.metadata();
                var doc =
                        access.getDocument(
                                knowledgeBaseId, UUID.fromString(meta.getString("documentId")));
                if (!UUID.fromString(meta.getString("revisionId")).equals(doc.getActiveVersionId())
                        || doc.getState() == KnowledgeDocumentState.DELETING) {
                    continue;
                }
                String title = doc.getTitle();
                String location = meta.getString("location");
                int length = title.length() + location.length() + segment.text().length() + 120;
                if (chars + length > properties.getMaxContextChars()) {
                    continue;
                }
                chars += length;
                hits.add(
                        new KnowledgeSearchHit(
                                knowledgeBaseId,
                                doc.getId(),
                                doc.getActiveVersionId(),
                                base.getProfile().generation(),
                                UUID.fromString(meta.getString("id")),
                                title,
                                segment.text(),
                                location,
                                meta.getDouble("score"),
                                "/api/knowledge-bases/"
                                        + knowledgeBaseId
                                        + "/documents/"
                                        + doc.getId()
                                        + "/versions/"
                                        + doc.getActiveVersionId()
                                        + "/download"));
            }
            if (!access.getKnowledgeBase(knowledgeBaseId)
                    .getProfile()
                    .generation()
                    .equals(base.getProfile().generation())) {
                return List.of();
            }
            return List.copyOf(hits);
        }
    }
}
