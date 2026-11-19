package com.mindrails.backend.asset.config;

import com.mindrails.backend.agent.registry.ReadOnlySessionRegistry;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.registry.MysqlAssetRegistry;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.asset.store.AssetBlobStore;
import com.mindrails.backend.asset.store.LocalAssetBlobStore;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;
import com.mindrails.backend.knowledge.service.KnowledgeBaseService;
import com.mindrails.backend.knowledge.service.KnowledgeDocumentService;
import com.mindrails.backend.knowledge.service.KnowledgeSearchService;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@Import(AssetPropertiesConfiguration.class)
@EnableConfigurationProperties(KnowledgeProperties.class)
public class AssetStorageConfiguration {
    @Bean
    AssetRegistry assetRegistry(@Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc,
            ObjectMapper json, AssetStorageProperties properties) {
        return new MysqlAssetRegistry(jdbc, json, properties.location());
    }

    @Bean(destroyMethod = "close")
    AssetBlobStore assetBlobStore(AssetStorageProperties properties) {
        if (properties.getMaxUploadBytes() < 1
                || properties.getDirectTextChars() < 1
                || properties.getContextChars() < properties.getDirectTextChars())
            throw new IllegalArgumentException("Invalid asset limits");
        return new LocalAssetBlobStore(properties.directory());
    }

    @Bean
    DefaultAssetStorageService assetStorageService(AssetRegistry registry, AssetBlobStore blobs,
            AssetStorageProperties properties, ObjectMapper json) {
        return new DefaultAssetStorageService(registry, blobs, properties, json);
    }

    @Bean
    AssetKnowledgeService assetKnowledgeService(AssetStorageService assets, AssetRegistry registry,
            @Nullable KnowledgeDocumentService documents, @Nullable KnowledgeBaseService bases) {
        return new AssetKnowledgeService(assets, registry, documents, bases);
    }

    @Bean
    AssetQueryService assetQueryService(AssetStorageService assets, AssetRegistry registry,
            AssetKnowledgeService knowledge) {
        return new DefaultAssetQueryService(assets, registry, knowledge);
    }

    @Bean(destroyMethod = "close")
    AssetWorker assetWorker(AssetRegistry registry, DefaultAssetStorageService assets,
            AssetKnowledgeService knowledge, AssetStorageProperties properties,
            KnowledgeProperties knowledgeProperties) {
        return new AssetWorker(registry, assets, knowledge, properties,
                new DocumentParserRegistry(knowledgeProperties));
    }

    @Bean
    @Order(0)
    AssetConversationRetriever assetConversationRetriever(AssetStorageService assets, AssetRegistry registry,
            AssetKnowledgeService knowledge, @Nullable KnowledgeSearchService search,
            ReadOnlySessionRegistry sessions, AssetStorageProperties properties) {
        return new AssetConversationRetriever(assets, registry, knowledge, search, sessions, properties);
    }
}
