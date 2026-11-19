package com.mindrails.backend.knowledge.config;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.embedding.EmbeddingProviderRegistry;
import com.mindrails.backend.embedding.QdrantProperties;
import com.mindrails.backend.knowledge.KnowledgeRetriever;
import com.mindrails.backend.knowledge.index.KnowledgeIndexFactory;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;
import com.mindrails.backend.knowledge.registry.MysqlKnowledgeRegistry;
import com.mindrails.backend.knowledge.service.DefaultKnowledgeBaseService;
import com.mindrails.backend.knowledge.service.DefaultKnowledgeDocumentService;
import com.mindrails.backend.knowledge.service.DefaultKnowledgeSearchService;
import com.mindrails.backend.knowledge.service.KnowledgeBaseService;
import com.mindrails.backend.knowledge.service.KnowledgeBindingService;
import com.mindrails.backend.knowledge.service.KnowledgeDocumentService;
import com.mindrails.backend.knowledge.service.KnowledgeSearchService;
import com.mindrails.backend.knowledge.service.internal.KnowledgeAccess;
import com.mindrails.backend.knowledge.service.internal.KnowledgeCleanupService;
import com.mindrails.backend.knowledge.service.internal.KnowledgeIngestionService;
import com.mindrails.backend.knowledge.service.internal.KnowledgeJobRunner;
import com.mindrails.backend.knowledge.service.internal.KnowledgePublicationService;
import com.mindrails.backend.knowledge.service.internal.KnowledgeTaskService;
import com.mindrails.backend.knowledge.service.internal.KnowledgeWorker;
import com.mindrails.backend.knowledge.store.DocumentStorage;
import com.mindrails.backend.knowledge.store.LocalDocumentStorage;
import com.mindrails.backend.knowledge.tool.KnowledgeTools;
import com.mindrails.backend.metrics.MetricsRecorder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Lazy;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({KnowledgeProperties.class, QdrantProperties.class, FileStorageProperties.class})
public class KnowledgeConfiguration {
    @Bean
    KnowledgeTools knowledgeTools(
            @Lazy KnowledgeDocumentService documents,
            KnowledgeBindingService bindings,
            KnowledgeProperties properties,
            MetricsRecorder metricsRecorder) {
        return new KnowledgeTools(documents, bindings, properties, metricsRecorder);
    }

    @Bean
    KnowledgeRegistry knowledgeRegistry(
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc, ObjectMapper mapper) {
        return new MysqlKnowledgeRegistry(jdbc, mapper);
    }

    @Bean
    DocumentStorage assetDocumentStorage(
            FileStorageProperties files,
            KnowledgeProperties properties,
            com.mindrails.backend.asset.service.AssetStorageService assets,
            com.mindrails.backend.asset.store.AssetBlobStore blobs,
            com.mindrails.backend.asset.registry.AssetRegistry registry) {
        return new com.mindrails.backend.asset.store.AssetDocumentStorage(
                assets, blobs, registry, new LocalDocumentStorage(files, properties));
    }

    @Bean
    DocumentParserRegistry documentParserRegistry(KnowledgeProperties properties) {
        return new DocumentParserRegistry(properties);
    }

    @Bean
    KnowledgeIndexFactory knowledgeIndexFactory(
            EmbeddingProviderRegistry providers,
            QdrantProperties properties,
            ObjectMapper mapper) {
        return new KnowledgeIndexFactory(providers, properties, mapper);
    }

    @Bean
    KnowledgeAccess knowledgeAccess(
            KnowledgeRegistry registry, KnowledgeBindingService bindings) {
        return new KnowledgeAccess(registry, bindings);
    }

    @Bean
    KnowledgeTaskService knowledgeTaskService(
            KnowledgeAccess access, KnowledgeProperties properties) {
        return new KnowledgeTaskService(access, properties);
    }

    @Bean
    KnowledgeCleanupService knowledgeCleanupService(
            KnowledgeAccess access,
            DocumentStorage storage,
            KnowledgeIndexFactory indexes,
            KnowledgeTaskService tasks) {
        return new KnowledgeCleanupService(access, storage, indexes, tasks);
    }

    @Bean
    KnowledgePublicationService knowledgePublicationService(
            KnowledgeAccess access,
            KnowledgeIndexFactory indexes,
            KnowledgeTaskService tasks,
            KnowledgeCleanupService cleanup) {
        return new KnowledgePublicationService(access, indexes, tasks, cleanup);
    }

    @Bean
    KnowledgeIngestionService knowledgeIngestionService(
            KnowledgeAccess access,
            KnowledgeProperties properties,
            DocumentStorage storage,
            DocumentParserRegistry parsers,
            KnowledgeIndexFactory indexes,
            KnowledgeTaskService tasks,
            KnowledgePublicationService publication,
            ObjectMapper mapper) {
        return new KnowledgeIngestionService(
                access, properties, storage, parsers, indexes, tasks, publication, mapper);
    }

    @Bean
    KnowledgeJobRunner knowledgeJobRunner(
            KnowledgeIngestionService ingestion,
            KnowledgeCleanupService cleanup,
            KnowledgeTaskService tasks) {
        return new KnowledgeJobRunner(ingestion, cleanup, tasks);
    }

    @Bean
    KnowledgeBaseService knowledgeBaseService(
            KnowledgeAccess access,
            KnowledgeProperties properties,
            KnowledgeIndexFactory indexes,
            KnowledgeBindingService bindings,
            KnowledgePublicationService publication) {
        return new DefaultKnowledgeBaseService(
                access, properties, indexes, bindings, publication);
    }

    @Bean
    KnowledgeDocumentService knowledgeDocumentService(
            KnowledgeAccess access,
            KnowledgeProperties properties,
            DocumentStorage storage,
            DocumentParserRegistry parsers,
            KnowledgeBindingService bindings,
            KnowledgeTaskService tasks,
            ObjectMapper mapper) {
        return new DefaultKnowledgeDocumentService(
                access, properties, storage, parsers, bindings, tasks, mapper);
    }

    @Bean
    KnowledgeSearchService knowledgeSearchService(
            KnowledgeAccess access,
            KnowledgeProperties properties,
            KnowledgeIndexFactory indexes) {
        return new DefaultKnowledgeSearchService(access, properties, indexes);
    }

    @Bean
    @Order(20)
    KnowledgeRetriever knowledgeRetriever(
            KnowledgeSearchService search,
            KnowledgeBindingService bindings) {
        return new KnowledgeRetriever(search, bindings);
    }

    @Bean(destroyMethod = "close")
    KnowledgeWorker knowledgeWorker(
            KnowledgeTaskService tasks,
            KnowledgeJobRunner runner,
            KnowledgeCleanupService cleanup,
            KnowledgeProperties properties) {
        return new KnowledgeWorker(tasks, runner, cleanup, properties);
    }
}
