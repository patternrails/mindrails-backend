package com.mindrails.backend.knowledge.api.web;

import java.util.List;

public record KnowledgeTextRequest(String key, String title, String content, List<String> tags) {}
