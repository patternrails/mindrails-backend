package com.mindrails.backend.asset.model;

import java.time.Instant;
import java.util.UUID;

public record Asset(
        UUID id, UUID ownerId, String title, String kind, Instant createdAt, UUID knowledgeBaseId) {
    public Asset(UUID id, UUID ownerId, String title, String kind, Instant createdAt) {
        this(id, ownerId, title, kind, createdAt, null);
    }

    @com.fasterxml.jackson.annotation.JsonProperty(
            value = "inKnowledgeBase",
            access = com.fasterxml.jackson.annotation.JsonProperty.Access.READ_ONLY)
    public boolean inKnowledgeBase() {
        return knowledgeBaseId != null;
    }
}
