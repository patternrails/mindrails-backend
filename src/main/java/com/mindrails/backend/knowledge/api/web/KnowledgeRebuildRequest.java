package com.mindrails.backend.knowledge.api.web;

public record KnowledgeRebuildRequest(
        String embeddingProvider, Integer chunkChars, Integer overlap, Long version) {}
