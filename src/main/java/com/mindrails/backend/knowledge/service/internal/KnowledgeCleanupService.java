package com.mindrails.backend.knowledge.service.internal;

import com.mindrails.backend.common.ExceptionDiagnostics;
import com.mindrails.backend.knowledge.index.KnowledgeIndexFactory;
import com.mindrails.backend.knowledge.model.DocumentVersionState;
import com.mindrails.backend.knowledge.model.IngestionJobState;
import com.mindrails.backend.knowledge.model.KnowledgeIndexProfile;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;
import com.mindrails.backend.knowledge.store.DocumentStorage;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Slf4j
public final class KnowledgeCleanupService {
    private final KnowledgeAccess access;
    private final KnowledgeRegistry registry;
    private final DocumentStorage storage;
    private final KnowledgeIndexFactory indexes;
    private final KnowledgeTaskService tasks;

    public KnowledgeCleanupService(
            KnowledgeAccess access,
            DocumentStorage storage,
            KnowledgeIndexFactory indexes,
            KnowledgeTaskService tasks) {
        this.access = access;
        registry = access.registry();
        this.storage = storage;
        this.indexes = indexes;
        this.tasks = tasks;
    }

    public void cleanup(KnowledgeIngestionJob lease) {
        var running =
                registry.listJobs(lease.getKnowledgeBaseId()).stream()
                        .anyMatch(
                                job ->
                                        !job.getId().equals(lease.getId())
                                                && job.getState() == IngestionJobState.RUNNING
                                                && (lease.getDocumentId() == null
                                                        || lease.getDocumentId()
                                                                .equals(job.getDocumentId())));
        if (running) {
            registry.transaction(
                    () -> {
                        var job = tasks.requireCurrentLease(lease);
                        job.setState(IngestionJobState.QUEUED);
                        job.setAttempts(Math.max(0, job.getAttempts() - 1));
                        job.setLeaseUntil(Instant.now().plusSeconds(3));
                        registry.save(job);
                        return null;
                    });
            return;
        }
        var docs =
                lease.getDocumentId() == null
                        ? registry.listDocuments(lease.getKnowledgeBaseId())
                        : List.of(registry.getDocument(lease.getDocumentId()));
        for (var doc : docs) {
            for (var revision : registry.listDocumentVersions(doc.getId())) {
                try (var index =
                        indexes.forCleanup(revision.getProfile(), doc.getKnowledgeBaseId())) {
                    index.removeRevision(revision.getId());
                }
                storage.delete(revision.getStorageKey());
                storage.delete(revision.getTextKey());
                storage.delete(revision.getId() + "/parsed.txt");
            }
        }
        if (lease.getDocumentId() == null) {
            var base = access.getKnowledgeBase(lease.getKnowledgeBaseId());
            var profiles = new HashSet<KnowledgeIndexProfile>();
            profiles.add(base.getProfile());
            if (base.getPendingProfile() != null) {
                profiles.add(base.getPendingProfile());
            }
            for (var doc : docs) {
                registry.listDocumentVersions(doc.getId())
                        .forEach(revision -> profiles.add(revision.getProfile()));
            }
            for (var profile : profiles) {
                try (var old = indexes.forCleanup(profile, base.getId())) {
                    old.drop();
                }
            }
        }
        registry.transaction(
                () -> {
                    tasks.requireCurrentLease(lease);
                    storage.unlink(lease.getKnowledgeBaseId(), lease.getDocumentId());
                    if (lease.getDocumentId() == null) {
                        registry.deleteKnowledgeBase(lease.getKnowledgeBaseId());
                    } else {
                        registry.deleteDocument(lease.getDocumentId());
                        var job = tasks.requireCurrentLease(lease);
                        job.setState(IngestionJobState.DONE);
                        job.setLeaseUntil(null);
                        registry.save(job);
                    }
                    return null;
                });
    }

    public void maintenance(int cursor) {
        var bases = registry.listKnowledgeBases();
        if (!bases.isEmpty()) {
            cleanupSuperseded(bases.get(Math.floorMod(cursor, bases.size())).getId());
        }
    }

    public void cleanupSuperseded(UUID knowledgeBaseId) {
        try {
            var base = access.getKnowledgeBase(knowledgeBaseId);
            for (var doc : registry.listDocuments(knowledgeBaseId)) {
                for (var revision : registry.listDocumentVersions(doc.getId())) {
                    if (List.of(DocumentVersionState.SUPERSEDED, DocumentVersionState.CANCELLED)
                            .contains(revision.getState())) {
                        try (var index =
                                indexes.forCleanup(revision.getProfile(), knowledgeBaseId)) {
                            index.removeRevision(revision.getId());
                        }
                        if (!revision.getProfile()
                                        .collection()
                                        .equals(base.getProfile().collection())
                                && (base.getPendingProfile() == null
                                        || !revision.getProfile()
                                                .collection()
                                                .equals(base.getPendingProfile().collection()))) {
                            try (var old =
                                    indexes.forCleanup(revision.getProfile(), knowledgeBaseId)) {
                                old.drop();
                            }
                        }
                        revision.setState(DocumentVersionState.ARCHIVED);
                        registry.save(revision);
                    }
                }
            }
        } catch (Exception e) {
            log.debug(
                    "Knowledge index cleanup will retry: {}",
                    knowledgeBaseId,
                    ExceptionDiagnostics.forLogging(e));
        }
    }
}
