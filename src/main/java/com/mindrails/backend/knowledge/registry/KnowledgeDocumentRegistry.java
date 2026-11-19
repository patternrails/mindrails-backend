package com.mindrails.backend.knowledge.registry;

import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;

import java.util.List;
import java.util.UUID;

public interface KnowledgeDocumentRegistry {
    KnowledgeDocument getDocument(UUID id);

    KnowledgeDocumentVersion getDocumentVersion(UUID id);

    List<KnowledgeDocument> listDocuments(UUID knowledgeBaseId);

    List<KnowledgeDocumentVersion> listDocumentVersions(UUID doc);

    void insert(KnowledgeDocument e);

    void insert(KnowledgeDocumentVersion e);

    void save(KnowledgeDocument e);

    void save(KnowledgeDocumentVersion e);

    void deleteDocument(UUID id);
}
