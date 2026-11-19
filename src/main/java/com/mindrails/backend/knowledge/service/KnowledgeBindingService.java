package com.mindrails.backend.knowledge.service;

import com.mindrails.backend.knowledge.model.KnowledgeBinding;

import java.util.List;
import java.util.UUID;

public interface KnowledgeBindingService {
    KnowledgeBinding agent(UUID agentId);

    KnowledgeBinding session(UUID sessionId);

    void check(KnowledgeBinding binding);

    void checkSession(UUID sessionId, KnowledgeBinding expected);

    KnowledgeBinding bind(UUID agentId, List<UUID> knowledgeBaseIds, long expectedVersion);

    KnowledgeBinding assign(UUID agentId, UUID knowledgeBaseId, boolean assigned, long expectedVersion);

    List<KnowledgeBinding> referencing(UUID knowledgeBaseId);
}
