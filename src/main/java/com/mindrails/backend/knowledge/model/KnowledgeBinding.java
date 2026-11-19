package com.mindrails.backend.knowledge.model;

import java.util.List;
import java.util.UUID;

/** Immutable snapshot of an Agent's current knowledge access. */
public record KnowledgeBinding(UUID agentId, List<UUID> knowledgeBaseIds, long agentVersion) {
    public KnowledgeBinding {
        knowledgeBaseIds = List.copyOf(knowledgeBaseIds);
    }
}
