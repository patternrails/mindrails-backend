package com.mindrails.backend.knowledge.model;

import java.util.List;

public record KnowledgeDocumentDetails(
        KnowledgeDocument document, List<KnowledgeDocumentVersion> versions) {}
