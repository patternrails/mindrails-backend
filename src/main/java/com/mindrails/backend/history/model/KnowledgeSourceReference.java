package com.mindrails.backend.history.model;

import java.util.UUID;

public record KnowledgeSourceReference(
        UUID knowledgeBaseId,
        UUID documentId,
        UUID revisionId,
        UUID indexGeneration,
        UUID chunkId,
        double score) {}
