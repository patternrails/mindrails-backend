package com.mindrails.backend.knowledge.api.web;

import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentDetails;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;

import java.util.List;

public record KnowledgeDocumentDetailResponse(
        KnowledgeDocument document, List<KnowledgeDocumentVersion> versions) {
    public static KnowledgeDocumentDetailResponse from(KnowledgeDocumentDetails data) {
        return new KnowledgeDocumentDetailResponse(data.document(), data.versions());
    }
}
