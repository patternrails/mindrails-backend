package com.mindrails.backend.knowledge.registry;

import com.mindrails.backend.knowledge.model.KnowledgeBase;

import java.util.List;
import java.util.UUID;

public interface KnowledgeBaseRegistry {
    KnowledgeBase getKnowledgeBase(UUID id);

    List<KnowledgeBase> listKnowledgeBases();

    void lockKnowledgeBase(UUID id);

    void insert(KnowledgeBase e);

    void save(KnowledgeBase e);

    void deleteKnowledgeBase(UUID id);
}
