package com.mindrails.backend.provider;

import java.math.BigDecimal;
import java.util.UUID;

/** Persistable configuration, separated from runtime models and resolved credentials. */
public record ProviderDefinition(
        UUID id,
        String name,
        String chatModel,
        Boolean streaming,
        String baseUrl,
        String apiKey,
        String modelName,
        Boolean reasoning,
        String thinkingLevel,
        BigDecimal modelCost,
        Integer contextWindow,
        Integer maxTokens) {
    public ProviderDefinition withApiKey(String value) {
        return new ProviderDefinition(
                id,
                name,
                chatModel,
                streaming,
                baseUrl,
                value,
                modelName,
                reasoning,
                thinkingLevel,
                modelCost,
                contextWindow,
                maxTokens);
    }

    @Override
    public String toString() {
        return "ProviderDefinition[" + name + "]";
    }
}
