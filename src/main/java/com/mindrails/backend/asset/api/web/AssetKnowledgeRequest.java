package com.mindrails.backend.asset.api.web;

import java.util.List;
import java.util.UUID;

public record AssetKnowledgeRequest(UUID knowledgeBaseId, String key, String title, List<String> tags) {}
