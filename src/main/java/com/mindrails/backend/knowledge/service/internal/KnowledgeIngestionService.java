package com.mindrails.backend.knowledge.service.internal;

import com.mindrails.backend.embedding.EmbeddingProvider;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.index.KnowledgeIndex;
import com.mindrails.backend.knowledge.index.KnowledgeIndexFactory;
import com.mindrails.backend.knowledge.index.ProviderEmbeddingModel;
import com.mindrails.backend.knowledge.model.DocumentBlock;
import com.mindrails.backend.knowledge.model.DocumentChunk;
import com.mindrails.backend.knowledge.model.DocumentVersionState;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.parser.DocumentChunker;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;
import com.mindrails.backend.knowledge.service.KnowledgeException;
import com.mindrails.backend.knowledge.store.DocumentStorage;

import dev.langchain4j.data.document.Document;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.store.embedding.EmbeddingStoreIngestor;

import tools.jackson.databind.ObjectMapper;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.stream.Collectors;

public final class KnowledgeIngestionService {
    private static final int EMBEDDING_BATCH_SIZE = 10;
    private final DocumentChunker chunker;
    private final KnowledgeAccess access;
    private final KnowledgeRegistry registry;
    private final KnowledgeProperties properties;
    private final DocumentStorage storage;
    private final DocumentParserRegistry parsers;
    private final KnowledgeIndexFactory indexes;
    private final KnowledgeTaskService tasks;
    private final KnowledgePublicationService publication;
    private final ObjectMapper mapper;

    public KnowledgeIngestionService(
            KnowledgeAccess access,
            KnowledgeProperties properties,
            DocumentStorage storage,
            DocumentParserRegistry parsers,
            KnowledgeIndexFactory indexes,
            KnowledgeTaskService tasks,
            KnowledgePublicationService publication,
            ObjectMapper mapper) {
        this.access = access;
        registry = access.registry();
        this.properties = properties;
        this.chunker = new DocumentChunker(properties);
        this.storage = storage;
        this.parsers = parsers;
        this.indexes = indexes;
        this.tasks = tasks;
        this.publication = publication;
        this.mapper = mapper;
    }

    public void process(KnowledgeIngestionJob lease) {
        var revision = registry.getDocumentVersion(lease.getRevisionId());
        var doc = access.getDocument(lease.getKnowledgeBaseId(), lease.getDocumentId());
        storage.link(
                revision.getId(), doc.getKnowledgeBaseId(), doc.getId(), revision.getStorageKey());
        if (lease.getAttempts() > properties.getMaxAttempts()) {
            throw KnowledgeException.invalid("Task retry limit reached; retry manually");
        }
        tasks.phase(lease, DocumentVersionState.PARSING, 0, 0);
        var blocks = storage.blocks(revision.getStorageKey());
        if (blocks == null)
            blocks = parsers.parse(storage.path(revision.getStorageKey()), revision.getFilename());
        tasks.phase(lease, DocumentVersionState.CHUNKING, 0, 0);
        var chunks =
                chunker.split(
                        revision.getId(),
                        blocks,
                        revision.getProfile().chunkChars(),
                        revision.getProfile().overlap());
        String chunksKey = writeParsedContent(revision, blocks, chunks);
        saveParsedContentKey(lease, chunksKey);
        embedChunks(lease, doc, revision, chunks);
        publication.publish(lease);
    }

    private String writeParsedContent(
            KnowledgeDocumentVersion revision,
            List<DocumentBlock> blocks,
            List<DocumentChunk> chunks) {
        String parsedText =
                blocks.stream()
                        .map(block -> block.location() + "\n" + block.text())
                        .collect(Collectors.joining("\n\n"));
        storage.text(revision.getId() + "/parsed.txt", parsedText);
        String chunksKey = revision.getId() + "/chunks.json";
        storage.text(chunksKey, mapper.writeValueAsString(chunks));
        return chunksKey;
    }

    private void saveParsedContentKey(KnowledgeIngestionJob lease, String chunksKey) {
        registry.transaction(
                () -> {
                    tasks.requireActiveLease(lease);
                    var current = registry.getDocumentVersion(lease.getRevisionId());
                    current.setTextKey(chunksKey);
                    registry.save(current);
                    return null;
                });
    }

    private void embedChunks(
            KnowledgeIngestionJob lease,
            KnowledgeDocument document,
            KnowledgeDocumentVersion revision,
            List<DocumentChunk> chunks) {
        tasks.phase(lease, DocumentVersionState.EMBEDDING, chunks.size(), 0);
        var provider = indexes.provider(revision.getProfile());
        try (var index =
                indexes.forIngestion(revision.getProfile(), document.getKnowledgeBaseId())) {
            index.removeRevision(revision.getId());
            for (int start = 0; start < chunks.size(); start += EMBEDDING_BATCH_SIZE) {
                tasks.requireActiveLease(lease);
                var batch =
                        chunks.subList(
                                start, Math.min(start + EMBEDDING_BATCH_SIZE, chunks.size()));
                var segments =
                        batch.stream().map(chunk -> segment(document, revision, chunk)).toList();
                ingestBatch(index, provider, segments);
                tasks.phase(
                        lease, DocumentVersionState.EMBEDDING, chunks.size(), start + batch.size());
            }
        }
    }

    private void ingestBatch(
            KnowledgeIndex index, EmbeddingProvider provider, List<TextSegment> segments) {
        var ingestor =
                EmbeddingStoreIngestor.builder()
                        .embeddingModel(new ProviderEmbeddingModel(provider))
                        .embeddingStore(index)
                        .documentSplitter(ignored -> segments)
                        .build();
        ingestor.ingest(Document.from("Structured document batch"));
    }

    private TextSegment segment(
            KnowledgeDocument document, KnowledgeDocumentVersion revision, DocumentChunk chunk) {
        var metadata = new LinkedHashMap<String, Object>();
        metadata.put("id", chunk.id().toString());
        metadata.put("knowledgeBaseId", document.getKnowledgeBaseId().toString());
        metadata.put("documentId", document.getId().toString());
        metadata.put("revisionId", revision.getId().toString());
        metadata.put("generation", revision.getProfile().generation().toString());
        metadata.put("title", document.getTitle());
        metadata.put("location", chunk.location());
        metadata.put("index", chunk.index());
        return TextSegment.from(chunk.text(), Metadata.from(metadata));
    }
}
