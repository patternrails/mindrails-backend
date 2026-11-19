package com.mindrails.backend.embedding;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;


class EmbeddingProviderCrudTest {

    private EmbeddingProviderDefinition d(String name) {
        return new EmbeddingProviderDefinition(
                name,
                "openai",
                "embed-model",
                "http://localhost:9000/v1",
                "private-test-secret",
                1024,
                1000,
                0);
    }

    @Test
    void cloneReusesEncryptedKeyAndLeavesOriginalIndependent() throws Exception {
        var mapper = JsonMapper.builder().build();
        try (var registry = TestEmbeddingCatalog.open(mapper, d("original"))) {
            var knowledge = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            var service = new EmbeddingProviderService(registry, knowledge);
            var cloned = service.clone("original", d("original_copy").withApiKey(null));
            assertInstanceOf(com.mindrails.backend.common.ServiceResult.Success.class, cloned);
            assertEquals("private-test-secret", registry.definition("original_copy").orElseThrow().apiKey());
            assertFalse(mapper.writeValueAsString(cloned).contains("private-test-secret"));
            assertInstanceOf(com.mindrails.backend.common.ServiceResult.Failure.class, service.clone("original", d("original_copy").withApiKey(null)));
            assertInstanceOf(com.mindrails.backend.common.ServiceResult.Failure.class, service.clone("missing", d("new").withApiKey(null)));
            assertTrue(registry.definition("original").isPresent());
        }
    }

    @Test
    void rejectsInvalidDimensionsKeysAndUrls() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        EmbeddingProviderRegistry.validate(
                                new EmbeddingProviderDefinition(
                                        "bad",
                                        "openai",
                                        "model",
                                        "https://example.com",
                                        "plaintext",
                                        0,
                                        null,
                                        null)));
    }
}
