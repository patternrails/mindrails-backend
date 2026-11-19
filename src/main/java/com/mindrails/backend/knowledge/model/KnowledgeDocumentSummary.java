package com.mindrails.backend.knowledge.model;

public record KnowledgeDocumentSummary(
        KnowledgeDocument document, KnowledgeDocumentVersion revision) {}
