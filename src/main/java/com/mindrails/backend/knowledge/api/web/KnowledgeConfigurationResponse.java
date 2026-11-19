package com.mindrails.backend.knowledge.api.web;

import com.mindrails.backend.embedding.EmbeddingProviderRegistry;

import java.util.List;

public record KnowledgeConfigurationResponse(
        boolean enabled,
        String defaultEmbeddingProvider,
        List<EmbeddingProviderRegistry.Info> providers) {}
