package com.mindrails.backend.knowledge.model;

import java.util.UUID;

public record KnowledgeIndexProfile(
        String provider,
        String modelName,
        String fingerprint,
        int dimensions,
        String collection,
        UUID generation,
        int chunkChars,
        int overlap) {}
