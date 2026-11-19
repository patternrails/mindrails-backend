package com.mindrails.backend.knowledge.api.web;

import java.util.List;
import java.util.UUID;

public record KnowledgeBindingRequest(List<UUID> knowledgeBaseIds, Long version) {}
