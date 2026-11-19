package com.mindrails.backend.knowledge.model;

import java.util.UUID;

public interface VersionedKnowledgeEntity {
    UUID getId();

    long getVersion();

    void setVersion(long value);
}
