package com.mindrails.backend.knowledge.index;

import com.mindrails.backend.embedding.EmbeddingProvider;
import com.mindrails.backend.embedding.EmbeddingProviderRegistry;
import com.mindrails.backend.embedding.QdrantProperties;
import com.mindrails.backend.knowledge.model.KnowledgeIndexProfile;
import com.mindrails.backend.knowledge.model.KnowledgeSearchScope;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

/** Creates scoped vector adapters; services do not construct Qdrant transports. */
public final class KnowledgeIndexFactory {
    private final EmbeddingProviderRegistry embeddings;
    private final QdrantProperties settings;
    private final ObjectMapper mapper;

    public KnowledgeIndexFactory(
            EmbeddingProviderRegistry embeddings, QdrantProperties settings, ObjectMapper mapper) {
        this.embeddings = embeddings;
        this.settings = settings;
        this.mapper = mapper;
    }

    public KnowledgeIndexProfile profile(
            UUID knowledgeBaseId, String provider, int size, int overlap) {
        if (size < 256 || size > 6000 || overlap < 0 || overlap >= size) {
            throw KnowledgeException.invalid("Invalid chunk size or overlap");
        }
        if (provider == null) provider = embeddings.requireDefaultProviderName();
        var embedding = embeddings.require(provider);
        var generation = UUID.randomUUID();
        return new KnowledgeIndexProfile(
                provider,
                embedding.modelName(),
                embedding.fingerprint(),
                embedding.dimensions(),
                "knowledge_"
                        + knowledgeBaseId.toString().replace("-", "")
                        + "_"
                        + generation.toString().replace("-", ""),
                generation,
                size,
                overlap);
    }

    public EmbeddingProvider provider(KnowledgeIndexProfile profile) {
        var provider = embeddings.require(profile.provider());
        if (!provider.fingerprint().equals(profile.fingerprint())
                || provider.dimensions() != profile.dimensions()) {
            throw KnowledgeException.conflict(
                    "Embedding configuration changed; rebuild this knowledge base");
        }
        return provider;
    }

    public KnowledgeIndex forIngestion(KnowledgeIndexProfile profile, UUID knowledgeBaseId) {
        return new QdrantDocumentStore(profile, knowledgeBaseId, null, settings, mapper, true);
    }

    public KnowledgeIndex forSearch(KnowledgeSearchScope scope) {
        return new QdrantDocumentStore(
                scope.profile(),
                scope.knowledgeBaseId(),
                scope.publishedVersions(),
                settings,
                mapper,
                false);
    }

    public KnowledgeIndex forCleanup(KnowledgeIndexProfile profile, UUID knowledgeBaseId) {
        return new QdrantDocumentStore(profile, knowledgeBaseId, null, settings, mapper, false);
    }
}
