package com.mindrails.backend.knowledge.api.web;

import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentSummary;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentVersion;

public record KnowledgeDocumentSummaryResponse(
        KnowledgeDocument document, KnowledgeDocumentVersion revision) {
    public static KnowledgeDocumentSummaryResponse from(KnowledgeDocumentSummary data) {
        return new KnowledgeDocumentSummaryResponse(data.document(), data.revision());
    }
}
