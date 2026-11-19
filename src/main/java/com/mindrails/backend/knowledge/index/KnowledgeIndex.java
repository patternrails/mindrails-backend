package com.mindrails.backend.knowledge.index;

import java.util.UUID;

public interface KnowledgeIndex
        extends dev.langchain4j.store.embedding.EmbeddingStore<
                        dev.langchain4j.data.segment.TextSegment>,
                AutoCloseable {
    void removeRevision(UUID revisionId);

    void drop();

    @Override
    void close();
}
