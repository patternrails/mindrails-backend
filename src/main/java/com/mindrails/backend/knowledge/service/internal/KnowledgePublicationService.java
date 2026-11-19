package com.mindrails.backend.knowledge.service.internal;

import static com.mindrails.backend.knowledge.service.internal.KnowledgeValidation.checkVersion;

import com.mindrails.backend.knowledge.index.KnowledgeIndexFactory;
import com.mindrails.backend.knowledge.model.DocumentVersionState;
import com.mindrails.backend.knowledge.model.IngestionJobState;
import com.mindrails.backend.knowledge.model.IngestionJobType;
import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.model.KnowledgeBaseState;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentState;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;

public final class KnowledgePublicationService {
    private final KnowledgeAccess access;
    private final KnowledgeRegistry registry;
    private final KnowledgeIndexFactory indexes;
    private final KnowledgeTaskService tasks;
    private final KnowledgeCleanupService cleanup;

    public KnowledgePublicationService(
            KnowledgeAccess access,
            KnowledgeIndexFactory indexes,
            KnowledgeTaskService tasks,
            KnowledgeCleanupService cleanup) {
        this.access = access;
        registry = access.registry();
        this.indexes = indexes;
        this.tasks = tasks;
        this.cleanup = cleanup;
    }

    public KnowledgeBase rebuild(
            UUID knowledgeBaseId, String provider, Integer size, Integer overlap, long version) {
        var snapshot = access.getKnowledgeBase(knowledgeBaseId);
        var next =
                indexes.profile(
                        knowledgeBaseId,
                        provider == null ? snapshot.getProfile().provider() : provider,
                        size == null ? snapshot.getProfile().chunkChars() : size,
                        overlap == null ? snapshot.getProfile().overlap() : overlap);
        return registry.transaction(
                () -> {
                    registry.lockKnowledgeBase(knowledgeBaseId);
                    var base = access.requireEditableBase(knowledgeBaseId);
                    checkVersion(base, version);
                    var docs = registry.listDocuments(knowledgeBaseId);
                    for (var doc : docs) {
                        access.requireIdleDocument(doc);
                        if (doc.getActiveVersionId() == null) {
                            throw KnowledgeException.conflict(
                                    "All documents must have a ready version before rebuilding");
                        }
                    }
                    base.setPendingProfile(next);
                    base.setState(KnowledgeBaseState.REBUILDING);
                    registry.save(base);
                    for (var doc : docs) {
                        var old = registry.getDocumentVersion(doc.getActiveVersionId());
                        var revisionId = UUID.randomUUID();
                        var revision =
                                old.toBuilder()
                                        .id(revisionId)
                                        .profile(next)
                                        .state(DocumentVersionState.QUEUED)
                                        .textKey(null)
                                        .chunkCount(0)
                                        .processedChunks(0)
                                        .error(null)
                                        .version(0)
                                        .createdAt(Instant.now())
                                        .updatedAt(Instant.now())
                                        .build();
                        registry.insert(revision);
                        doc.setPendingVersionId(revisionId);
                        doc.setState(KnowledgeDocumentState.QUEUED);
                        registry.save(doc);
                        tasks.enqueue(doc, revisionId, IngestionJobType.INGEST);
                    }
                    if (docs.isEmpty()) {
                        base.setProfile(next);
                        base.setPendingProfile(null);
                        base.setState(KnowledgeBaseState.ACTIVE);
                        registry.save(base);
                    }
                    return base;
                });
    }

    public KnowledgeBase cancelRebuild(UUID knowledgeBaseId, long version) {
        return registry.transaction(
                () -> {
                    registry.lockKnowledgeBase(knowledgeBaseId);
                    var base = access.getKnowledgeBase(knowledgeBaseId);
                    checkVersion(base, version);
                    if (base.getPendingProfile() == null) {
                        throw KnowledgeException.conflict("No rebuild is in progress");
                    }
                    for (var job : registry.listJobs(knowledgeBaseId)) {
                        if (!List.of(IngestionJobState.DONE, IngestionJobState.FAILED)
                                .contains(job.getState())) {
                            job.setState(IngestionJobState.FAILED);
                            job.setError("Index rebuild cancelled");
                            job.setLeaseUntil(null);
                            registry.save(job);
                        }
                    }
                    for (var doc : registry.listDocuments(knowledgeBaseId)) {
                        if (doc.getPendingVersionId() != null) {
                            var revision = registry.getDocumentVersion(doc.getPendingVersionId());
                            revision.setState(DocumentVersionState.CANCELLED);
                            registry.save(revision);
                            doc.setPendingVersionId(null);
                            doc.setState(
                                    doc.getActiveVersionId() == null
                                            ? KnowledgeDocumentState.FAILED
                                            : KnowledgeDocumentState.READY);
                            registry.save(doc);
                        }
                    }
                    base.setPendingProfile(null);
                    base.setState(KnowledgeBaseState.ACTIVE);
                    registry.save(base);
                    return base;
                });
    }

    public void publish(KnowledgeIngestionJob lease) {
        registry.transaction(
                () -> {
                    tasks.requireActiveLease(lease);
                    var base = access.getKnowledgeBase(lease.getKnowledgeBaseId());
                    var doc = access.getDocument(base.getId(), lease.getDocumentId());
                    var revision = registry.getDocumentVersion(lease.getRevisionId());
                    if (!revision.getId().equals(doc.getPendingVersionId())) {
                        throw new CancellationException("Superseded task");
                    }
                    revision.setState(DocumentVersionState.READY);
                    revision.setError(null);
                    registry.save(revision);
                    if (base.getPendingProfile() == null) {
                        activate(doc, revision);
                    } else {
                        var docs = registry.listDocuments(base.getId());
                        boolean ready = docs.stream().allMatch(this::pendingVersionReady);
                        if (ready) {
                            activateGeneration(base, docs);
                        }
                    }
                    var job = tasks.requireCurrentLease(lease);
                    job.setState(IngestionJobState.DONE);
                    job.setLeaseUntil(null);
                    job.setError(null);
                    registry.save(job);
                    return null;
                });
        cleanup.cleanupSuperseded(lease.getKnowledgeBaseId());
    }

    private void activate(KnowledgeDocument doc, KnowledgeDocumentVersion revision) {
        if (doc.getActiveVersionId() != null) {
            var old = registry.getDocumentVersion(doc.getActiveVersionId());
            old.setState(DocumentVersionState.SUPERSEDED);
            registry.save(old);
        }
        doc.setActiveVersionId(revision.getId());
        doc.setPendingVersionId(null);
        doc.setState(KnowledgeDocumentState.READY);
        doc.setUpdatedAt(Instant.now());
        registry.save(doc);
    }

    private boolean pendingVersionReady(KnowledgeDocument document) {
        if (document.getPendingVersionId() == null) {
            return false;
        }
        return registry.getDocumentVersion(document.getPendingVersionId()).getState()
                == DocumentVersionState.READY;
    }

    private void activateGeneration(KnowledgeBase base, List<KnowledgeDocument> documents) {
        for (var document : documents) {
            activate(document, registry.getDocumentVersion(document.getPendingVersionId()));
        }
        base.setProfile(base.getPendingProfile());
        base.setPendingProfile(null);
        base.setState(KnowledgeBaseState.ACTIVE);
        base.setUpdatedAt(Instant.now());
        registry.save(base);
    }
}
