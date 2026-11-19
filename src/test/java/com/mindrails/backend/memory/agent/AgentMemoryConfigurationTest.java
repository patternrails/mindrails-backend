package com.mindrails.backend.memory.agent;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.embedding.EmbeddingProvider;
import com.mindrails.backend.embedding.EmbeddingProviderRegistry;
import com.mindrails.backend.embedding.QdrantProperties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class AgentMemoryConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(AgentMemoryConfiguration.class)
            .withBean(AgentRegistry.class, () -> mock(AgentRegistry.class))
            .withBean(SessionRegistry.class, () -> mock(SessionRegistry.class))
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withBean("persistenceJdbcTemplate", org.springframework.jdbc.core.JdbcTemplate.class,
                    com.mindrails.backend.embedding.TestEmbeddingCatalog::database);

    @Test
    void backendCanStartWithNoDefaultAndMemoryBecomesAvailableAfterConfiguration() {
        var embeddings = mock(EmbeddingProviderRegistry.class);
        when(embeddings.defaultProviderName()).thenReturn(java.util.Optional.empty());
        runner.withBean(EmbeddingProviderRegistry.class, () -> embeddings).run(context -> {
            assertThat(context).hasNotFailed();
            var memory = context.getBean(AgentMemoryService.class);
            assertThat(memory.available()).isFalse();
            when(embeddings.defaultProviderName()).thenReturn(java.util.Optional.of("configured"));
            assertThat(memory.available()).isTrue();
        });
    }

    @Test
    void qdrantStoreUsesSharedConnectionSettingsAndItsOwnCollection() {
        var embeddings = mock(EmbeddingProviderRegistry.class);
        var embedding = mock(EmbeddingProvider.class);
        when(embeddings.defaultProviderName()).thenReturn(java.util.Optional.of("custom-default"));
        when(embeddings.require("custom-default")).thenReturn(embedding);
        try (var stores = mockConstruction(QdrantAgentMemoryStore.class, (store, construction) -> {
            var shared = (QdrantProperties) construction.arguments().getFirst();
            assertThat(shared.getHost()).isEqualTo("shared-qdrant");
            assertThat(shared.getPort()).isEqualTo(6334);
            assertThat(shared.isTls()).isTrue();
            assertThat(shared.getApiKey()).isEqualTo("test-key");
            assertThat(shared.getTimeoutMillis()).isEqualTo(8000);
            assertThat(construction.arguments().get(1)).isEqualTo("test_agent_memory");
            assertThat(construction.arguments().get(2)).isSameAs(embedding);
        })) {
            runner.withBean(EmbeddingProviderRegistry.class, () -> embeddings)
                    .withPropertyValues("mindrails.qdrant.host=shared-qdrant", "mindrails.qdrant.port=6334",
                            "mindrails.qdrant.tls=true", "mindrails.qdrant.api-key=test-key",
                            "mindrails.qdrant.timeout-millis=8000", "mindrails.agent-memory.qdrant.collection=test_agent_memory")
                    .run(context -> {
                        assertThat(context).hasNotFailed().hasSingleBean(QdrantProperties.class);
                        assertThat(stores.constructed()).hasSize(1);
                        assertThat(context.getBean(AgentMemoryStore.class)).isSameAs(stores.constructed().getFirst());
                    });
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"mindrails.agent-memory.max-entry-chars=0",
            "mindrails.agent-memory.max-entries=0", "mindrails.agent-memory.retrieval.max-results=0",
            "mindrails.agent-memory.retrieval.max-context-chars=511"})
    void rejectsInvalidSettings(String setting) {
        runner.withBean(AgentMemoryStore.class, InMemoryAgentMemoryStore::new)
                .withPropertyValues(setting)
                .run(context -> assertThat(context).hasFailed());
    }

}
