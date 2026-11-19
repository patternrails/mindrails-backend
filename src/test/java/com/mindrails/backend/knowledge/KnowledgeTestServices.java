package com.mindrails.backend.knowledge;

import com.mindrails.backend.embedding.EmbeddingProviderRegistry;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.index.KnowledgeIndexFactory;
import com.mindrails.backend.knowledge.model.DocumentSubmission;
import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.model.KnowledgeBinding;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.model.KnowledgeSearchHit;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;
import com.mindrails.backend.knowledge.service.DefaultKnowledgeBaseService;
import com.mindrails.backend.knowledge.service.DefaultKnowledgeDocumentService;
import com.mindrails.backend.knowledge.service.DefaultKnowledgeSearchService;
import com.mindrails.backend.knowledge.service.KnowledgeBaseService;
import com.mindrails.backend.knowledge.service.KnowledgeBindingService;
import com.mindrails.backend.knowledge.service.KnowledgeDocumentService;
import com.mindrails.backend.knowledge.service.KnowledgeResults;
import com.mindrails.backend.knowledge.service.KnowledgeSearchService;
import com.mindrails.backend.knowledge.service.internal.KnowledgeAccess;
import com.mindrails.backend.knowledge.service.internal.KnowledgeCleanupService;
import com.mindrails.backend.knowledge.service.internal.KnowledgeIngestionService;
import com.mindrails.backend.knowledge.service.internal.KnowledgeJobRunner;
import com.mindrails.backend.knowledge.service.internal.KnowledgePublicationService;
import com.mindrails.backend.knowledge.service.internal.KnowledgeTaskService;
import com.mindrails.backend.knowledge.tool.KnowledgeTools;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.knowledge.store.DocumentStorage;
import com.mindrails.backend.embedding.QdrantProperties;
import tools.jackson.databind.ObjectMapper;
import java.io.InputStream;
import java.util.List;
import java.util.UUID;

/** Integration harness only. Production consumers use narrow service interfaces. */
final class KnowledgeTestServices {
    final KnowledgeBaseService bases;
    final KnowledgeDocumentService documents;
    final KnowledgeSearchService search;
    final KnowledgeJobRunner runner;
    final TestKnowledge conversation;

    KnowledgeTestServices(
            KnowledgeRegistry registry,
            KnowledgeProperties properties,
            EmbeddingProviderRegistry embeddings,
            QdrantProperties qdrant,
            DocumentStorage storage,
            DocumentParserRegistry parsers,
            KnowledgeBindingService bindings,
            ObjectMapper mapper) {
        var access = new KnowledgeAccess(registry, bindings);
        var indexes = new KnowledgeIndexFactory(embeddings, qdrant, mapper);
        var tasks = new KnowledgeTaskService(access, properties);
        var cleanup = new KnowledgeCleanupService(access, storage, indexes, tasks);
        var publication = new KnowledgePublicationService(access, indexes, tasks, cleanup);
        bases = new DefaultKnowledgeBaseService(access, properties, indexes, bindings, publication);
        documents =
                new DefaultKnowledgeDocumentService(
                        access, properties, storage, parsers, bindings, tasks, mapper);
        search = new DefaultKnowledgeSearchService(access, properties, indexes);
        runner =
                new KnowledgeJobRunner(
                        new KnowledgeIngestionService(
                                access,
                                properties,
                                storage,
                                parsers,
                                indexes,
                                tasks,
                                publication,
                                mapper),
                        cleanup,
                        tasks);
        conversation = new TestKnowledge(new KnowledgeRetriever(search, bindings),
                new KnowledgeTools(documents, bindings, properties, TestMetrics.disabled()), properties.getCombinedContextChars());
    }

    KnowledgeBase create(String name, String description, String provider) {
        return KnowledgeResults.require(bases.create(name, description, provider));
    }

    KnowledgeBase getKnowledgeBase(UUID id) {
        return KnowledgeResults.require(bases.get(id));
    }

    KnowledgeBase rebuild(UUID id, String provider, Integer size, Integer overlap, long version) {
        return KnowledgeResults.require(bases.rebuild(id, provider, size, overlap, version));
    }

    KnowledgeBase cancelRebuild(UUID id, long version) {
        return KnowledgeResults.require(bases.cancelRebuild(id, version));
    }

    KnowledgeIngestionJob deleteBase(UUID id, long version) {
        return KnowledgeResults.require(bases.delete(id, version));
    }

    List<KnowledgeBinding> references(UUID id) {
        return KnowledgeResults.require(bases.detail(id)).references();
    }

    DocumentSubmission text(
            UUID id,
            String key,
            String title,
            String content,
            List<String> tags,
            KnowledgeBinding binding,
            UUID session,
            String turn) {
        return KnowledgeResults.require(
                documents.createText(id, key, title, content, tags, binding, session, turn));
    }

    KnowledgeDocument getDocument(UUID kb, UUID id) {
        return KnowledgeResults.require(documents.get(kb, id));
    }

    DocumentSubmission replace(UUID kb, UUID id, String filename, InputStream input, long version) {
        return KnowledgeResults.require(documents.replace(kb, id, filename, input, version));
    }

    java.nio.file.Path download(UUID kb, UUID id) {
        return KnowledgeResults.require(documents.download(kb, id));
    }

    DocumentSubmission deleteDoc(UUID kb, UUID id, long version) {
        return KnowledgeResults.require(documents.delete(kb, id, version));
    }

    List<KnowledgeSearchHit> search(UUID kb, String query) {
        return KnowledgeResults.require(search.search(kb, query));
    }

    void process(KnowledgeIngestionJob job) {
        runner.process(job);
    }
}
