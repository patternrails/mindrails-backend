package com.mindrails.backend.knowledge.api.web;

import com.mindrails.backend.knowledge.service.KnowledgeException;

/** Validation for transport fields which are optional only during deserialization. */
final class KnowledgeRequests {
    private KnowledgeRequests() {}

    static long version(Long value) {
        if (value == null || value < 0) {
            throw KnowledgeException.invalid("Current version is required");
        }
        return value;
    }
}
