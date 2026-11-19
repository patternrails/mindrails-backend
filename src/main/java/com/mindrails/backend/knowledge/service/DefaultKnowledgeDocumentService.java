package com.mindrails.backend.knowledge.service;

import static com.mindrails.backend.knowledge.service.internal.KnowledgeValidation.checkVersion;
import static com.mindrails.backend.knowledge.service.internal.KnowledgeValidation.normalizeTags;
import static com.mindrails.backend.knowledge.service.internal.KnowledgeValidation.validateDoc;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.model.DocumentChunk;
import com.mindrails.backend.knowledge.model.DocumentSubmission;
import com.mindrails.backend.knowledge.model.DocumentVersionState;
import com.mindrails.backend.knowledge.model.IngestionJobType;
import com.mindrails.backend.knowledge.model.KnowledgeBaseState;
import com.mindrails.backend.knowledge.model.KnowledgeBinding;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentDetails;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentState;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentSummary;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentUpload;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;
import com.mindrails.backend.knowledge.model.KnowledgeIndexProfile;
import com.mindrails.backend.knowledge.model.KnowledgePage;
import com.mindrails.backend.knowledge.model.KnowledgeSourceType;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;
import com.mindrails.backend.knowledge.service.internal.KnowledgeAccess;
import com.mindrails.backend.knowledge.service.internal.KnowledgePages;
import com.mindrails.backend.knowledge.service.internal.KnowledgeTaskService;
import com.mindrails.backend.knowledge.store.DocumentStorage;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public final class DefaultKnowledgeDocumentService implements KnowledgeDocumentService {
    private final KnowledgeAccess access;
    private final KnowledgeRegistry registry;
    private final KnowledgeProperties properties;
    private final DocumentStorage storage;
    private final DocumentParserRegistry parsers;
    private final KnowledgeBindingService bindings;
    private final KnowledgeTaskService tasks;
    private final ObjectMapper mapper;

    public DefaultKnowledgeDocumentService(
            KnowledgeAccess access,
            KnowledgeProperties properties,
            DocumentStorage storage,
            DocumentParserRegistry parsers,
            KnowledgeBindingService bindings,
            KnowledgeTaskService tasks,
            ObjectMapper mapper) {
        this.access = access;
        registry = access.registry();
        this.properties = properties;
        this.storage = storage;
        this.parsers = parsers;
        this.bindings = bindings;
        this.tasks = tasks;
        this.mapper = mapper;
    }

    @Override
    public ServiceResult<KnowledgeDocument> get(UUID knowledgeBaseId, UUID id) {
        return KnowledgeResults.attempt(() -> access.getDocument(knowledgeBaseId, id));
    }

    @Override
    public ServiceResult<List<KnowledgeDocument>> list(UUID knowledgeBaseId) {
        return KnowledgeResults.attempt(() -> access.listDocuments(knowledgeBaseId));
    }

    @Override
    public ServiceResult<KnowledgeDocumentVersion> currentVersion(KnowledgeDocument document) {
        return KnowledgeResults.attempt(() -> access.getDocumentVersion(document));
    }

    @Override
    public ServiceResult<List<KnowledgeDocumentVersion>> versions(UUID knowledgeBaseId, UUID id) {
        return KnowledgeResults.attempt(() -> access.listDocumentVersions(knowledgeBaseId, id));
    }

    @Override
    public ServiceResult<DocumentSubmission> upload(KnowledgeDocumentUpload upload) {
        return KnowledgeResults.attempt(
                () ->
                        executeSubmit(
                                upload.knowledgeBaseId(),
                                upload.key(),
                                upload.title(),
                                upload.tags(),
                                upload.filename(),
                                upload.content(),
                                upload.assetVersionId(),
                                KnowledgeSourceType.UPLOAD,
                                null,
                                null,
                                null));
    }

    private DocumentSubmission executeSubmit(
            UUID knowledgeBaseId,
            String key,
            String title,
            List<String> tags,
            String filename,
            InputStream input,
            UUID assetVersionId,
            KnowledgeSourceType source,
            KnowledgeBinding binding,
            UUID sessionId,
            String turnId) {
        validateDoc(key, title, tags);
        access.requireEditableBase(knowledgeBaseId);
        var id = UUID.randomUUID();
        var stored =
                assetVersionId == null
                        ? storage.write(
                                id, filename, DocumentParserRegistry.extension(filename), input)
                        : storage.reference(assetVersionId);
        try {
            String mediaType = mediaType(stored.key());
            return registry.transaction(
                    () -> {
                        registry.lockKnowledgeBase(knowledgeBaseId);
                        var base = access.requireEditableBase(knowledgeBaseId);
                        if (binding != null) {
                            bindings.check(binding);
                            if (!binding.knowledgeBaseIds().contains(knowledgeBaseId)) {
                                throw KnowledgeException.conflict("Knowledge binding changed");
                            }
                        }
                        var docs = registry.listDocuments(knowledgeBaseId);
                        var existing =
                                docs.stream()
                                        .filter(doc -> doc.getKey().equals(key))
                                        .findFirst()
                                        .orElse(null);
                        if (existing != null) {
                            return existingSubmission(existing, stored, title, tags);
                        }
                        if (docs.size() >= properties.getMaxDocuments()) {
                            throw KnowledgeException.conflict(
                                    "Knowledge base document limit reached");
                        }
                        var now = Instant.now();
                        var doc =
                                KnowledgeDocument.builder()
                                        .id(UUID.randomUUID())
                                        .knowledgeBaseId(knowledgeBaseId)
                                        .key(key)
                                        .title(title.strip())
                                        .tags(normalizeTags(tags))
                                        .sourceType(source)
                                        .sourceAgentId(binding == null ? null : binding.agentId())
                                        .sourceSessionId(sessionId)
                                        .sourceTurnId(turnId)
                                        .pendingVersionId(id)
                                        .state(KnowledgeDocumentState.QUEUED)
                                        .createdAt(now)
                                        .updatedAt(now)
                                        .build();
                        registry.insert(doc);
                        var revision =
                                newRevision(
                                        id, doc, base.getProfile(), filename, stored, mediaType);
                        registry.insert(revision);
                        storage.link(
                                revision.getId(),
                                knowledgeBaseId,
                                doc.getId(),
                                revision.getStorageKey());
                        var job = tasks.enqueue(doc, id, IngestionJobType.INGEST);
                        return new DocumentSubmission(doc.getId(), job.getId(), "QUEUED", false);
                    });
        } finally { // Duplicate/failed submissions must not leak an unused source file.
            try {
                registry.getDocumentVersion(id);
            } catch (KnowledgeException missing) {
                storage.delete(stored.key());
            }
        }
    }

    private DocumentSubmission existingSubmission(
            KnowledgeDocument document,
            DocumentStorage.Stored stored,
            String title,
            List<String> tags) {
        var revision = access.getDocumentVersion(document);
        boolean sameContent = stored.hash().equals(revision.getHash());
        boolean sameMetadata =
                title.strip().equals(document.getTitle())
                        && normalizeTags(tags).equals(document.getTags());
        if (!sameContent || !sameMetadata) {
            throw KnowledgeException.conflict("Document key already exists with different content");
        }
        var job =
                registry.listJobs(document.getKnowledgeBaseId()).stream()
                        .filter(candidate -> revision.getId().equals(candidate.getRevisionId()))
                        .findFirst()
                        .orElse(null);
        return new DocumentSubmission(
                document.getId(),
                job == null ? null : job.getId(),
                revision.getState().name(),
                true);
    }

    @Override
    public ServiceResult<DocumentSubmission> createText(
            UUID knowledgeBaseId,
            String key,
            String title,
            String content,
            List<String> tags,
            KnowledgeBinding binding,
            UUID sessionId,
            String turnId) {
        return KnowledgeResults.attempt(
                () ->
                        executeCreateText(
                                knowledgeBaseId,
                                key,
                                title,
                                content,
                                tags,
                                binding,
                                sessionId,
                                turnId));
    }

    private DocumentSubmission executeCreateText(
            UUID knowledgeBaseId,
            String key,
            String title,
            String content,
            List<String> tags,
            KnowledgeBinding binding,
            UUID sessionId,
            String turnId) {
        if (content == null
                || content.isBlank()
                || content.length() > properties.getMaxTextChars()) {
            throw KnowledgeException.invalid("Text is empty or exceeds limit");
        }
        return executeSubmit(
                knowledgeBaseId,
                key,
                title,
                tags,
                "document.md",
                new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)),
                null,
                binding == null ? KnowledgeSourceType.TEXT : KnowledgeSourceType.MODEL,
                binding,
                sessionId,
                turnId);
    }

    @Override
    public ServiceResult<KnowledgeDocument> updateMetadata(
            UUID knowledgeBaseId, UUID id, String title, List<String> tags, long version) {
        return KnowledgeResults.attempt(
                () -> executeUpdateMetadata(knowledgeBaseId, id, title, tags, version));
    }

    private KnowledgeDocument executeUpdateMetadata(
            UUID knowledgeBaseId, UUID id, String title, List<String> tags, long version) {
        validateDoc("valid", title, tags);
        return registry.transaction(
                () -> {
                    registry.lockKnowledgeBase(knowledgeBaseId);
                    access.requireEditableBase(knowledgeBaseId);
                    var doc = access.getDocument(knowledgeBaseId, id);
                    checkVersion(doc, version);
                    doc.setTitle(title.strip());
                    doc.setTags(normalizeTags(tags));
                    doc.setUpdatedAt(Instant.now());
                    registry.save(doc);
                    return doc;
                });
    }

    @Override
    public ServiceResult<DocumentSubmission> replace(
            UUID knowledgeBaseId, UUID id, String filename, InputStream input, long version) {
        return KnowledgeResults.attempt(
                () -> executeReplace(knowledgeBaseId, id, filename, input, version));
    }

    private DocumentSubmission executeReplace(
            UUID knowledgeBaseId, UUID id, String filename, InputStream input, long version) {
        var revisionId = UUID.randomUUID();
        var stored =
                storage.write(
                        revisionId, filename, DocumentParserRegistry.extension(filename), input);
        try {
            String mediaType = mediaType(stored.key());
            return registry.transaction(
                    () -> {
                        registry.lockKnowledgeBase(knowledgeBaseId);
                        var base = access.requireEditableBase(knowledgeBaseId);
                        var doc = access.getDocument(knowledgeBaseId, id);
                        checkVersion(doc, version);
                        access.requireIdleDocument(doc);
                        var revision =
                                newRevision(
                                        revisionId,
                                        doc,
                                        base.getProfile(),
                                        filename,
                                        stored,
                                        mediaType);
                        registry.insert(revision);
                        storage.link(
                                revision.getId(),
                                knowledgeBaseId,
                                doc.getId(),
                                revision.getStorageKey());
                        doc.setPendingVersionId(revisionId);
                        doc.setState(KnowledgeDocumentState.QUEUED);
                        doc.setUpdatedAt(Instant.now());
                        registry.save(doc);
                        var job = tasks.enqueue(doc, revisionId, IngestionJobType.INGEST);
                        return new DocumentSubmission(id, job.getId(), "QUEUED", false);
                    });
        } finally {
            try {
                registry.getDocumentVersion(revisionId);
            } catch (KnowledgeException missing) {
                storage.delete(stored.key());
            }
        }
    }

    @Override
    public ServiceResult<DocumentSubmission> retry(UUID knowledgeBaseId, UUID id, long version) {
        return KnowledgeResults.attempt(() -> executeRetry(knowledgeBaseId, id, version));
    }

    private DocumentSubmission executeRetry(UUID knowledgeBaseId, UUID id, long version) {
        return registry.transaction(
                () -> {
                    registry.lockKnowledgeBase(knowledgeBaseId);
                    var base = access.getKnowledgeBase(knowledgeBaseId);
                    var doc = access.getDocument(knowledgeBaseId, id);
                    checkVersion(doc, version);
                    if (base.getState() == KnowledgeBaseState.DELETING
                            || doc.getState() == KnowledgeDocumentState.DELETING) {
                        var job = tasks.enqueue(doc, null, IngestionJobType.DELETE_DOCUMENT);
                        return new DocumentSubmission(id, job.getId(), "DELETING", false);
                    }
                    access.requireIdleDocument(doc);
                    var old = access.getDocumentVersion(doc);
                    var revisionId = UUID.randomUUID();
                    var profile =
                            base.getPendingProfile() != null
                                    ? base.getPendingProfile()
                                    : base.getProfile();
                    var next =
                            old.toBuilder()
                                    .id(revisionId)
                                    .profile(profile)
                                    .state(DocumentVersionState.QUEUED)
                                    .textKey(null)
                                    .error(null)
                                    .chunkCount(0)
                                    .processedChunks(0)
                                    .version(0)
                                    .createdAt(Instant.now())
                                    .updatedAt(Instant.now())
                                    .build();
                    registry.insert(next);
                    storage.link(next.getId(), knowledgeBaseId, doc.getId(), next.getStorageKey());
                    doc.setPendingVersionId(revisionId);
                    doc.setState(KnowledgeDocumentState.QUEUED);
                    registry.save(doc);
                    var job = tasks.enqueue(doc, revisionId, IngestionJobType.INGEST);
                    return new DocumentSubmission(id, job.getId(), "QUEUED", false);
                });
    }

    @Override
    public ServiceResult<DocumentSubmission> delete(UUID knowledgeBaseId, UUID id, long version) {
        return KnowledgeResults.attempt(() -> executeDelete(knowledgeBaseId, id, version));
    }

    private DocumentSubmission executeDelete(UUID knowledgeBaseId, UUID id, long version) {
        return registry.transaction(
                () -> {
                    registry.lockKnowledgeBase(knowledgeBaseId);
                    access.requireEditableBase(knowledgeBaseId);
                    var doc = access.getDocument(knowledgeBaseId, id);
                    checkVersion(doc, version);
                    doc.setState(KnowledgeDocumentState.DELETING);
                    registry.save(doc);
                    var job = tasks.enqueue(doc, null, IngestionJobType.DELETE_DOCUMENT);
                    return new DocumentSubmission(id, job.getId(), "DELETING", false);
                });
    }

    @Override
    public ServiceResult<KnowledgeDocumentVersion> downloadVersion(UUID knowledgeBaseId, UUID id) {
        return KnowledgeResults.attempt(() -> executeDownloadVersion(knowledgeBaseId, id));
    }

    private KnowledgeDocumentVersion executeDownloadVersion(UUID knowledgeBaseId, UUID id) {
        var doc = access.getDocument(knowledgeBaseId, id);
        return doc.getActiveVersionId() == null
                ? access.getDocumentVersion(doc)
                : registry.getDocumentVersion(doc.getActiveVersionId());
    }

    @Override
    public ServiceResult<Path> download(UUID knowledgeBaseId, UUID id) {
        return KnowledgeResults.attempt(() -> executeDownload(knowledgeBaseId, id));
    }

    private Path executeDownload(UUID knowledgeBaseId, UUID id) {
        var doc = access.getDocument(knowledgeBaseId, id);
        if (doc.getState() == KnowledgeDocumentState.DELETING
                || (access.getKnowledgeBase(knowledgeBaseId).getState()
                        == KnowledgeBaseState.DELETING)) {
            throw KnowledgeException.missing();
        }
        return storage.path(executeDownloadVersion(knowledgeBaseId, id).getStorageKey());
    }

    private KnowledgeDocumentVersion previewRevision(KnowledgeDocument doc) {
        var latest = access.getDocumentVersion(doc);
        return latest.getTextKey() == null && doc.getActiveVersionId() != null
                ? registry.getDocumentVersion(doc.getActiveVersionId())
                : latest;
    }

    @Override
    public ServiceResult<String> parsedText(UUID knowledgeBaseId, UUID id) {
        return KnowledgeResults.attempt(() -> executeParsedText(knowledgeBaseId, id));
    }

    private String executeParsedText(UUID knowledgeBaseId, UUID id) {
        var revision = previewRevision(access.getDocument(knowledgeBaseId, id));
        if (revision.getTextKey() == null) {
            return "";
        }
        return storage.text(revision.getId() + "/parsed.txt");
    }

    @Override
    public ServiceResult<List<DocumentChunk>> chunks(UUID knowledgeBaseId, UUID id) {
        return KnowledgeResults.attempt(() -> executeChunks(knowledgeBaseId, id));
    }

    private List<DocumentChunk> executeChunks(UUID knowledgeBaseId, UUID id) {
        var revision = previewRevision(access.getDocument(knowledgeBaseId, id));
        if (revision.getTextKey() == null) {
            return List.of();
        }
        return mapper.readValue(
                storage.text(revision.getTextKey()), new TypeReference<List<DocumentChunk>>() {});
    }

    private String mediaType(String key) {
        String known = storage.mediaType(key);
        return known == null ? parsers.mediaType(storage.path(key)) : known;
    }

    private KnowledgeDocumentVersion newRevision(
            UUID id,
            KnowledgeDocument doc,
            KnowledgeIndexProfile profile,
            String filename,
            DocumentStorage.Stored stored,
            String mediaType) {
        var now = Instant.now();
        return KnowledgeDocumentVersion.builder()
                .id(id)
                .documentId(doc.getId())
                .knowledgeBaseId(doc.getKnowledgeBaseId())
                .profile(profile)
                .filename(Path.of(filename.replace('\\', '/')).getFileName().toString())
                .mediaType(mediaType)
                .storageKey(stored.key())
                .hash(stored.hash())
                .bytes(stored.bytes())
                .state(DocumentVersionState.QUEUED)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    @Override
    public ServiceResult<KnowledgePage<KnowledgeDocumentSummary>> page(
            UUID knowledgeBaseId, int page, int size) {
        return KnowledgeResults.attempt(
                () -> {
                    var documents =
                            KnowledgePages.page(access.listDocuments(knowledgeBaseId), page, size);
                    var summaries =
                            documents.items().stream()
                                    .map(
                                            document ->
                                                    new KnowledgeDocumentSummary(
                                                            document,
                                                            access.getDocumentVersion(document)))
                                    .toList();
                    return new KnowledgePage<>(
                            summaries,
                            documents.total(),
                            documents.page(),
                            documents.size(),
                            documents.hasNext());
                });
    }

    @Override
    public ServiceResult<KnowledgeDocumentDetails> detail(UUID knowledgeBaseId, UUID documentId) {
        return KnowledgeResults.attempt(
                () ->
                        new KnowledgeDocumentDetails(
                                access.getDocument(knowledgeBaseId, documentId),
                                access.listDocumentVersions(knowledgeBaseId, documentId)));
    }
}
