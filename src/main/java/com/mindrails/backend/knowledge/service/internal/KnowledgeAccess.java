package com.mindrails.backend.knowledge.service.internal;

import com.mindrails.backend.knowledge.model.DocumentVersionState;
import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.model.KnowledgeBaseState;
import com.mindrails.backend.knowledge.model.KnowledgeBinding;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentState;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;
import com.mindrails.backend.knowledge.service.KnowledgeBindingService;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import java.util.List;
import java.util.UUID;

/** Scope checks and metadata lookup shared by knowledge use cases. */
public final class KnowledgeAccess {
    private final KnowledgeRegistry registry;
    private final KnowledgeBindingService bindings;

    public KnowledgeAccess(KnowledgeRegistry registry, KnowledgeBindingService bindings) {
        this.registry = registry;
        this.bindings = bindings;
    }

    public KnowledgeRegistry registry() {
        return registry;
    }

    public List<KnowledgeBase> listKnowledgeBases() {
        return registry.listKnowledgeBases();
    }

    public KnowledgeBase getKnowledgeBase(UUID id) {
        return registry.getKnowledgeBase(id);
    }

    public List<KnowledgeDocument> listDocuments(UUID knowledgeBaseId) {
        getKnowledgeBase(knowledgeBaseId);
        return registry.listDocuments(knowledgeBaseId);
    }

    public List<KnowledgeIngestionJob> listJobs(UUID knowledgeBaseId) {
        getKnowledgeBase(knowledgeBaseId);
        return registry.listJobs(knowledgeBaseId);
    }

    public KnowledgeDocument getDocument(UUID knowledgeBaseId, UUID id) {
        getKnowledgeBase(knowledgeBaseId);
        var doc = registry.getDocument(id);
        if (!knowledgeBaseId.equals(doc.getKnowledgeBaseId())) {
            throw KnowledgeException.missing();
        }
        return doc;
    }

    public KnowledgeDocumentVersion getDocumentVersion(KnowledgeDocument doc) {
        return registry.getDocumentVersion(
                doc.getPendingVersionId() != null
                        ? doc.getPendingVersionId()
                        : doc.getActiveVersionId());
    }

    public List<KnowledgeDocumentVersion> listDocumentVersions(UUID knowledgeBaseId, UUID id) {
        return registry.listDocumentVersions(getDocument(knowledgeBaseId, id).getId());
    }

    public List<KnowledgeBinding> references(UUID knowledgeBaseId) {
        getKnowledgeBase(knowledgeBaseId);
        return bindings.referencing(knowledgeBaseId);
    }

    public KnowledgeBase requireEditableBase(UUID knowledgeBaseId) {
        var base = getKnowledgeBase(knowledgeBaseId);
        if (base.getState() != KnowledgeBaseState.ACTIVE) {
            throw KnowledgeException.conflict("Knowledge base is rebuilding or deleting");
        }
        return base;
    }

    public void requireIdleDocument(KnowledgeDocument doc) {
        if (doc.getState() == KnowledgeDocumentState.DELETING) {
            throw KnowledgeException.conflict("Document is being deleted");
        }
        if (doc.getPendingVersionId() == null) {
            return;
        }
        var pending = registry.getDocumentVersion(doc.getPendingVersionId());
        if (pending.getState() != DocumentVersionState.FAILED
                && pending.getState() != DocumentVersionState.READY) {
            throw KnowledgeException.conflict("Document is processing");
        }
    }
}
