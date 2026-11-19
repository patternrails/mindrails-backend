package com.mindrails.backend.memory.agent;

import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.embedding.EmbeddingProviderRegistry;
import com.mindrails.backend.embedding.QdrantProperties;
import com.mindrails.backend.tool.spec.AgentMemoryTools;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;


@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({AgentMemoryProperties.class, QdrantProperties.class})
public class AgentMemoryConfiguration {
    @Bean
    @ConditionalOnMissingBean(AgentMemoryStore.class)
    AgentMemoryStore agentMemoryStore(
            AgentMemoryProperties properties,
            ObjectMapper mapper,
            EmbeddingProviderRegistry embeddings,
            @Qualifier("mindrails.qdrant-com.mindrails.backend.embedding.QdrantProperties")
                    QdrantProperties qdrant) {
        String collection = properties.getQdrant().getCollection();
        return embeddings.defaultProviderName()
                .map(name -> new QdrantAgentMemoryStore(qdrant, collection, embeddings.require(name), mapper))
                .orElseGet(() -> new QdrantAgentMemoryStore(qdrant, collection,
                        embeddings::defaultProviderName, embeddings::require, mapper));
    }

    @Bean(destroyMethod = "close")
    @ConditionalOnMissingBean(EmbeddingProviderRegistry.class)
    EmbeddingProviderRegistry embeddingProviderRegistry(
            ObjectMapper mapper,
            Environment env,
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        return new EmbeddingProviderRegistry(mapper, env, jdbc);
    }

    @Bean
    AgentMemoryService agentMemoryService(
            AgentRegistry agents,
            SessionRegistry sessions,
            AgentMemoryStore store,
            AgentMemoryProperties properties) {
        return new AgentMemoryService(agents, sessions, store, properties);
    }

    @Bean
    @Order(10)
    AgentMemoryRetriever agentMemoryRetriever(AgentMemoryService service) {
        return new AgentMemoryRetriever(service);
    }

    @Bean
    AgentMemoryTools agentMemoryTools(AgentMemoryService service) {
        return new AgentMemoryTools(service);
    }
}
