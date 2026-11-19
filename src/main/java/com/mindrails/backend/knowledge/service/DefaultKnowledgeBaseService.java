package com.mindrails.backend.knowledge.service;

import static com.mindrails.backend.knowledge.service.internal.KnowledgeValidation.checkVersion;
import static com.mindrails.backend.knowledge.service.internal.KnowledgeValidation.validName;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.index.KnowledgeIndexFactory;
import com.mindrails.backend.knowledge.model.IngestionJobState;
import com.mindrails.backend.knowledge.model.IngestionJobType;
import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.model.KnowledgeBaseDetails;
import com.mindrails.backend.knowledge.model.KnowledgeBaseState;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentState;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.model.KnowledgePage;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;
import com.mindrails.backend.knowledge.service.internal.KnowledgeAccess;
import com.mindrails.backend.knowledge.service.internal.KnowledgePages;
import com.mindrails.backend.knowledge.service.internal.KnowledgePublicationService;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

public final class DefaultKnowledgeBaseService implements KnowledgeBaseService {
    private final KnowledgeAccess access;
    private final KnowledgeRegistry registry;
    private final KnowledgeProperties properties;
    private final KnowledgeIndexFactory indexes;
    private final KnowledgeBindingService bindings;
    private final KnowledgePublicationService publication;

    public DefaultKnowledgeBaseService(
            KnowledgeAccess access,
            KnowledgeProperties properties,
            KnowledgeIndexFactory indexes,
            KnowledgeBindingService bindings,
            KnowledgePublicationService publication) {
        this.access = access;
        registry = access.registry();
        this.properties = properties;
        this.indexes = indexes;
        this.bindings = bindings;
        this.publication = publication;
    }

    @Override
    public ServiceResult<KnowledgePage<KnowledgeBase>> page(int page, int size) {
        return KnowledgeResults.attempt(
                () -> KnowledgePages.page(access.listKnowledgeBases(), page, size));
    }

    @Override
    public ServiceResult<List<KnowledgeBase>> list() {
        return KnowledgeResults.attempt(() -> access.listKnowledgeBases());
    }

    @Override
    public ServiceResult<KnowledgeBase> get(UUID id) {
        return KnowledgeResults.attempt(() -> access.getKnowledgeBase(id));
    }

    @Override
    public ServiceResult<List<KnowledgeIngestionJob>> listJobs(UUID id) {
        return KnowledgeResults.attempt(() -> access.listJobs(id));
    }

    @Override
    public ServiceResult<KnowledgeBase> rebuild(
            UUID id, String provider, Integer size, Integer overlap, long version) {
        return KnowledgeResults.attempt(
                () -> publication.rebuild(id, provider, size, overlap, version));
    }

    @Override
    public ServiceResult<KnowledgeBase> cancelRebuild(UUID id, long version) {
        return KnowledgeResults.attempt(() -> publication.cancelRebuild(id, version));
    }

    @Override
    public ServiceResult<KnowledgeBaseDetails> detail(UUID id) {
        return KnowledgeResults.attempt(() -> executeDetail(id));
    }

    private KnowledgeBaseDetails executeDetail(UUID id) {
        var documents = access.listDocuments(id);
        long bytes = 0, chunks = 0;
        int ready = 0;
        var originals = new HashSet<String>();
        for (var document : documents) {
            for (var revision : access.listDocumentVersions(id, document.getId())) {
                if (originals.add(revision.getStorageKey())) {
                    bytes += revision.getBytes();
                }
            }
            if (document.getActiveVersionId() != null
                    && document.getState() != KnowledgeDocumentState.DELETING) {
                ready++;
                chunks +=
                        registry.getDocumentVersion(document.getActiveVersionId()).getChunkCount();
            }
        }
        var base = access.getKnowledgeBase(id);
        return new KnowledgeBaseDetails(
                base,
                documents.size(),
                ready,
                bytes,
                chunks,
                chunks * base.getProfile().dimensions() * 4L,
                access.references(id));
    }

    @Override
    public ServiceResult<KnowledgeBase> create(String name, String description, String provider) {
        return KnowledgeResults.attempt(() -> executeCreate(name, description, provider));
    }

    private KnowledgeBase executeCreate(String name, String description, String provider) {
        validName(name);
        var id = UUID.randomUUID();
        var now = Instant.now();
        var profile =
                indexes.profile(
                        id,
                        provider,
                        properties.getChunkChars(),
                        properties.getChunkOverlap());
        var base =
                KnowledgeBase.builder().ownerId(com.mindrails.backend.identity.ResourceAccess.creationOwner())
                        .id(id)
                        .name(name.strip())
                        .description(description == null ? "" : description)
                        .profile(profile)
                        .state(KnowledgeBaseState.ACTIVE)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        registry.insert(base);
        return base;
    }

    @Override
    public ServiceResult<KnowledgeBase> update(
            UUID id, String name, String description, long version) {
        return KnowledgeResults.attempt(() -> executeUpdate(id, name, description, version));
    }

    private KnowledgeBase executeUpdate(UUID id, String name, String description, long version) {
        validName(name);
        return registry.transaction(
                () -> {
                    registry.lockKnowledgeBase(id);
                    var base = access.requireEditableBase(id);
                    checkVersion(base, version);
                    base.setName(name.strip());
                    base.setDescription(description == null ? "" : description);
                    base.setUpdatedAt(Instant.now());
                    registry.save(base);
                    return base;
                });
    }

    @Override
    public ServiceResult<KnowledgeIngestionJob> delete(UUID knowledgeBaseId, long version) {
        return KnowledgeResults.attempt(() -> executeDelete(knowledgeBaseId, version));
    }

    private KnowledgeIngestionJob executeDelete(UUID knowledgeBaseId, long version) {
        return registry.transaction(
                () -> {
                    registry.lockKnowledgeBase(knowledgeBaseId);
                    var base = access.getKnowledgeBase(knowledgeBaseId);
                    checkVersion(base, version);
                    if (!bindings.referencing(knowledgeBaseId).isEmpty()) {
                        throw KnowledgeException.conflict(
                                "Knowledge base is referenced by Agents; unbind them first");
                    }
                    base.setState(KnowledgeBaseState.DELETING);
                    registry.save(base);
                    for (var doc : registry.listDocuments(knowledgeBaseId)) {
                        doc.setState(KnowledgeDocumentState.DELETING);
                        registry.save(doc);
                    }
                    var now = Instant.now();
                    var job =
                            KnowledgeIngestionJob.builder()
                                    .id(UUID.randomUUID())
                                    .knowledgeBaseId(knowledgeBaseId)
                                    .kind(IngestionJobType.DELETE_BASE)
                                    .state(IngestionJobState.QUEUED)
                                    .createdAt(now)
                                    .updatedAt(now)
                                    .build();
                    registry.insert(job);
                    return job;
                });
    }
}
