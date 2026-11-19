package com.mindrails.backend.knowledge.model;

import java.util.UUID;

public record KnowledgeSearchHit(
        UUID knowledgeBaseId,
        UUID documentId,
        UUID revisionId,
        UUID indexGeneration,
        UUID chunkId,
        String title,
        String text,
        String location,
        double score,
        String url) {}
