package com.mindrails.backend.knowledge.api.web;

public record KnowledgeBaseRequest(
        String name, String description, String embeddingProvider, Long version) {}
