package com.mindrails.backend.knowledge.service.internal;

import com.mindrails.backend.common.ExceptionDiagnostics;
import com.mindrails.backend.embedding.JsonHttpClient;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.model.DocumentVersionState;
import com.mindrails.backend.knowledge.model.IngestionJobState;
import com.mindrails.backend.knowledge.model.IngestionJobType;
import com.mindrails.backend.knowledge.model.KnowledgeBaseState;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentState;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;

import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CancellationException;

@Slf4j
public final class KnowledgeTaskService {
    private final KnowledgeAccess access;
    private final KnowledgeRegistry registry;
    private final KnowledgeProperties properties;

    public KnowledgeTaskService(KnowledgeAccess access, KnowledgeProperties properties) {
        this.access = access;
        this.registry = access.registry();
        this.properties = properties;
    }

    public KnowledgeIngestionJob claimNextJob(String owner) {
        return registry.claimNextJob(owner, properties.getProcessingTimeoutMillis() + 30000);
    }

    public void fail(KnowledgeIngestionJob lease, Exception error) {
        try {
            registry.transaction(
                    () -> {
                        var job = requireCurrentLease(lease);
                        var failure = KnowledgeTaskFailure.from(error);
                        boolean retry =
                                failure.retryable()
                                        && job.getAttempts() < properties.getMaxAttempts();
                        String message = failure.message();
                        job.setState(retry ? IngestionJobState.QUEUED : IngestionJobState.FAILED);
                        job.setError(message);
                        job.setLeaseUntil(
                                retry ? Instant.now().plusSeconds(3L * job.getAttempts()) : null);
                        registry.save(job);
                        if (job.getRevisionId() != null) {
                            var revision = registry.getDocumentVersion(job.getRevisionId());
                            revision.setState(
                                    retry
                                            ? DocumentVersionState.QUEUED
                                            : DocumentVersionState.FAILED);
                            revision.setError(message);
                            registry.save(revision);
                            var doc = registry.getDocument(job.getDocumentId());
                            if (doc.getState() != KnowledgeDocumentState.DELETING
                                    && revision.getId().equals(doc.getPendingVersionId())) {
                                doc.setState(
                                        retry
                                                ? KnowledgeDocumentState.QUEUED
                                                : KnowledgeDocumentState.FAILED);
                                registry.save(doc);
                            }
                        }
                        return null;
                    });
        } catch (Exception lost) {
            log.debug(
                    "Knowledge task failure could not be persisted: {}",
                    lease.getId(),
                    ExceptionDiagnostics.forLogging(lost));
        }
        log.warn("Knowledge task {} failed", lease.getId(), ExceptionDiagnostics.forLogging(error));
    }

    public void requireActiveLease(KnowledgeIngestionJob lease) {
        JsonHttpClient.checkActive();
        registry.transaction(
                () -> {
                    registry.lockKnowledgeBase(lease.getKnowledgeBaseId());
                    requireCurrentLease(lease);
                    if ((access.getKnowledgeBase(lease.getKnowledgeBaseId()).getState()
                                    == KnowledgeBaseState.DELETING)
                            || (registry.getDocument(lease.getDocumentId()).getState()
                                    == KnowledgeDocumentState.DELETING)) {
                        throw new CancellationException("Document deleted");
                    }
                    return null;
                });
    }

    public KnowledgeIngestionJob requireCurrentLease(KnowledgeIngestionJob lease) {
        var job = registry.getJob(lease.getId());
        if (job.getState() != IngestionJobState.RUNNING
                || !Objects.equals(job.getLeaseOwner(), lease.getLeaseOwner())) {
            throw new CancellationException("Task lease expired");
        }
        return job;
    }

    public KnowledgeIngestionJob enqueue(
            KnowledgeDocument doc, UUID revision, IngestionJobType kind) {
        var now = Instant.now();
        var job =
                KnowledgeIngestionJob.builder()
                        .id(UUID.randomUUID())
                        .knowledgeBaseId(doc.getKnowledgeBaseId())
                        .documentId(doc.getId())
                        .revisionId(revision)
                        .kind(kind)
                        .state(IngestionJobState.QUEUED)
                        .createdAt(now)
                        .updatedAt(now)
                        .build();
        registry.insert(job);
        return job;
    }

    public void phase(
            KnowledgeIngestionJob lease, DocumentVersionState state, int count, int processed) {
        registry.transaction(
                () -> {
                    requireActiveLease(lease);
                    var revision = registry.getDocumentVersion(lease.getRevisionId());
                    revision.setState(state);
                    revision.setChunkCount(count);
                    revision.setProcessedChunks(processed);
                    revision.setUpdatedAt(Instant.now());
                    registry.save(revision);
                    return null;
                });
    }
}
