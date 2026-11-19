package com.mindrails.backend.knowledge.model;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;

public record KnowledgeSearchScope(
        UUID knowledgeBaseId, KnowledgeIndexProfile profile, Set<UUID> publishedVersions) {
    public KnowledgeSearchScope {
        Objects.requireNonNull(knowledgeBaseId);
        Objects.requireNonNull(profile);
        publishedVersions = Set.copyOf(publishedVersions);
    }
}
