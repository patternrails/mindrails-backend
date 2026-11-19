package com.mindrails.backend.embedding;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.index.KnowledgeIndexFactory;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;
import java.util.*;

class SystemEmbeddingDefaultTest {
    private final JsonMapper mapper = JsonMapper.builder().build();

    private EmbeddingProviderDefinition definition(String name, boolean isDefault) {
        return new EmbeddingProviderDefinition(name, "openai", "test-model", "http://localhost:9000/v1",
                "test-private-key", 3, 1000, 0, isDefault);
    }

    @Test
    @SuppressWarnings("unchecked")
    void defaultIsProtectedWhileKeysAndClonesRemainEditable() throws Exception {
        try (var registry = TestEmbeddingCatalog.open(mapper,
                definition("z-default", true), definition("a-other", false))) {
            var service = new EmbeddingProviderService(registry, mock(ObjectProvider.class));
            assertEquals("z-default", registry.requireDefaultProviderName());
            assertTrue(registry.list().getFirst().defaultProvider());
            assertEquals("z-default", registry.list().getFirst().name());
            assertTrue(mapper.readTree(mapper.writeValueAsString(registry.list())).get(0).path("defaultProvider").asBoolean());
            assertInstanceOf(ServiceResult.Failure.class, service.delete("z-default"));
            assertInstanceOf(ServiceResult.Failure.class, service.save("a-other", definition("a-other", true), false));
            assertInstanceOf(ServiceResult.Failure.class, service.save("z-default", definition("z-default", false), false));
            assertInstanceOf(ServiceResult.Failure.class, service.save("new-default", definition("new-default", true), true));
            var update = new EmbeddingProviderDefinition("z-default", "openai", "test-model", "http://localhost:9000/v1",
                    "rotated-private-key", 3, 2000, 1);
            assertInstanceOf(ServiceResult.Success.class, service.save("z-default", update, false));
            assertEquals("z-default", registry.requireDefaultProviderName());
            assertEquals("rotated-private-key", registry.definition("z-default").orElseThrow().apiKey());
            assertInstanceOf(ServiceResult.Failure.class, service.save("z-default", new EmbeddingProviderDefinition(
                    "z-default", "openai", "different-model", "http://localhost:9000/v1", null, 3, 2000, 1), false));
            assertInstanceOf(ServiceResult.Success.class, service.clone("z-default", definition("clone", false).withApiKey(null)));
            assertFalse(Boolean.TRUE.equals(registry.definition("clone").orElseThrow().defaultProvider()));
            assertEquals("rotated-private-key", registry.definition("clone").orElseThrow().apiKey());
            assertThrows(IllegalArgumentException.class, () -> registry.save(definition("second-default", true)));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void emptyDatabaseHasNoDefaultUntilTheFirstProviderIsSaved() throws Exception {
        try (var registry = TestEmbeddingCatalog.open(mapper)) {
            assertTrue(registry.list().isEmpty());
            assertThrows(IllegalStateException.class, registry::requireDefaultProviderName);
            var service = new EmbeddingProviderService(registry, mock(ObjectProvider.class));
            var first = new EmbeddingProviderDefinition("first", "openai", "test-model", "http://localhost:9000/v1",
                    "test-private-key", 3, 1000, 0);
            assertInstanceOf(ServiceResult.Success.class, service.save(first.name(), first, true));
            assertEquals("first", registry.requireDefaultProviderName());
            assertInstanceOf(ServiceResult.Success.class, service.save("second", definition("second", false), true));
            assertEquals("first", registry.requireDefaultProviderName());
        }
    }

    @Test
    void knowledgeDefaultsToTheSystemProviderButExplicitModelsStayIndependent() throws Exception {
        try (var registry = TestEmbeddingCatalog.open(mapper,
                definition("system-default", true), definition("kb-specific", false))) {
            var indexes = new KnowledgeIndexFactory(registry, new QdrantProperties(), mapper);
            var automatic = indexes.profile(UUID.randomUUID(), null, 1200, 160);
            var explicit = indexes.profile(UUID.randomUUID(), "kb-specific", 1200, 160);
            assertEquals("system-default", automatic.provider());
            assertEquals("kb-specific", explicit.provider());
            assertEquals("test-model", indexes.provider(explicit).modelName());
        }
    }
}
