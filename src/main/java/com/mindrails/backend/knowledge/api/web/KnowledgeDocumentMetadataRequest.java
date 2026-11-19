package com.mindrails.backend.knowledge.api.web;

import java.util.List;

public record KnowledgeDocumentMetadataRequest(String title, List<String> tags, Long version) {}
