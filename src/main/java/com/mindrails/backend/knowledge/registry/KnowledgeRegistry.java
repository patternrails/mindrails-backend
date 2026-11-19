package com.mindrails.backend.knowledge.registry;

import java.util.function.Supplier;

/** Transactional metadata boundary. Never performs filesystem or vector-store I/O. */
public interface KnowledgeRegistry
        extends KnowledgeBaseRegistry, KnowledgeDocumentRegistry, KnowledgeJobRegistry {
    <T> T transaction(Supplier<T> work);
}
