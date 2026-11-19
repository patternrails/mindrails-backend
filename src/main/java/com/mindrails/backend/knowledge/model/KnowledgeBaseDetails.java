package com.mindrails.backend.knowledge.model;

import java.util.List;

public record KnowledgeBaseDetails(
        KnowledgeBase base,
        int documents,
        int readyDocuments,
        long originalBytes,
        long chunks,
        long estimatedVectorBytes,
        List<KnowledgeBinding> references) {}
