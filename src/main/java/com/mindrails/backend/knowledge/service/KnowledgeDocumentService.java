package com.mindrails.backend.knowledge.service;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.model.DocumentChunk;
import com.mindrails.backend.knowledge.model.DocumentSubmission;
import com.mindrails.backend.knowledge.model.KnowledgeBinding;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentDetails;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentSummary;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentUpload;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;
import com.mindrails.backend.knowledge.model.KnowledgePage;

import java.io.InputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;

public interface KnowledgeDocumentService {
    ServiceResult<DocumentSubmission> upload(KnowledgeDocumentUpload upload);

    ServiceResult<DocumentSubmission> createText(
            UUID knowledgeBaseId,
            String key,
            String title,
            String content,
            List<String> tags,
            KnowledgeBinding binding,
            UUID sessionId,
            String turnId);

    ServiceResult<KnowledgeDocument> updateMetadata(
            UUID knowledgeBaseId, UUID id, String title, List<String> tags, long version);

    ServiceResult<DocumentSubmission> replace(
            UUID knowledgeBaseId, UUID id, String filename, InputStream input, long version);

    ServiceResult<DocumentSubmission> retry(UUID knowledgeBaseId, UUID id, long version);

    ServiceResult<DocumentSubmission> delete(UUID knowledgeBaseId, UUID id, long version);

    ServiceResult<KnowledgeDocumentVersion> downloadVersion(UUID knowledgeBaseId, UUID id);

    ServiceResult<Path> download(UUID knowledgeBaseId, UUID id);

    ServiceResult<String> parsedText(UUID knowledgeBaseId, UUID id);

    ServiceResult<List<DocumentChunk>> chunks(UUID knowledgeBaseId, UUID id);

    ServiceResult<KnowledgeDocument> get(UUID knowledgeBaseId, UUID documentId);

    ServiceResult<List<KnowledgeDocument>> list(UUID knowledgeBaseId);

    ServiceResult<KnowledgeDocumentVersion> currentVersion(KnowledgeDocument document);

    ServiceResult<List<KnowledgeDocumentVersion>> versions(UUID knowledgeBaseId, UUID documentId);

    ServiceResult<KnowledgePage<KnowledgeDocumentSummary>> page(
            UUID knowledgeBaseId, int page, int size);

    ServiceResult<KnowledgeDocumentDetails> detail(UUID knowledgeBaseId, UUID documentId);
}
