package com.mindrails.backend.knowledge.registry;

import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class MysqlKnowledgeDocumentRegistry extends JdbcKnowledgeSnapshots
        implements KnowledgeDocumentRegistry {
    public MysqlKnowledgeDocumentRegistry(JdbcTemplate jdbc, ObjectMapper mapper) {
        super(jdbc, mapper);
    }

    public KnowledgeDocument getDocument(UUID id) {
        return one("knowledge_documents", id, KnowledgeDocument.class);
    }

    public KnowledgeDocumentVersion getDocumentVersion(UUID id) {
        return one("knowledge_document_versions", id, KnowledgeDocumentVersion.class);
    }

    public List<KnowledgeDocument> listDocuments(UUID knowledgeBaseId) {
        return query(
                "knowledge_documents",
                " WHERE knowledge_base_id=? ORDER BY created_at DESC,id",
                KnowledgeDocument.class,
                knowledgeBaseId.toString());
    }

    public List<KnowledgeDocumentVersion> listDocumentVersions(UUID doc) {
        return query(
                "knowledge_document_versions",
                " WHERE document_id=? ORDER BY created_at,id",
                KnowledgeDocumentVersion.class,
                doc.toString());
    }

    public void insert(KnowledgeDocument e) {
        insert(
                "knowledge_documents",
                e,
                e.getState().name(),
                e.getCreatedAt(),
                Map.of(
                        "knowledge_base_id",
                        e.getKnowledgeBaseId().toString(),
                        "entry_key",
                        e.getKey()));
    }

    public void insert(KnowledgeDocumentVersion e) {
        insert(
                "knowledge_document_versions",
                e,
                e.getState().name(),
                e.getCreatedAt(),
                Map.of(
                        "knowledge_base_id",
                        e.getKnowledgeBaseId().toString(),
                        "document_id",
                        e.getDocumentId().toString()));
    }

    public void save(KnowledgeDocument e) {
        save("knowledge_documents", e, e.getState().name());
    }

    public void save(KnowledgeDocumentVersion e) {
        save("knowledge_document_versions", e, e.getState().name());
    }

    public void deleteDocument(UUID id) {
        jdbc.update("DELETE FROM knowledge_documents WHERE id=?", id.toString());
    }
}
