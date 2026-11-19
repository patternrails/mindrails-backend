package com.mindrails.backend.embedding;

/** Writable configuration; credentials are encrypted by the storage boundary. */
public record EmbeddingProviderDefinition(
        String name,
        String type,
        String modelName,
        String baseUrl,
        String apiKey,
        int dimensions,
        Integer timeoutMillis,
        Integer maxRetries,
        Boolean defaultProvider) {
    public EmbeddingProviderDefinition(String name, String type, String modelName, String baseUrl,
            String apiKey, int dimensions, Integer timeoutMillis, Integer maxRetries) {
        this(name, type, modelName, baseUrl, apiKey, dimensions, timeoutMillis, maxRetries, null);
    }
    public EmbeddingProviderDefinition withApiKey(String value) {
        return new EmbeddingProviderDefinition(
                name, type, modelName, baseUrl, value, dimensions, timeoutMillis, maxRetries, defaultProvider);
    }

    @Override
    public String toString() {
        return "EmbeddingProviderDefinition[" + name + "]";
    }
}
