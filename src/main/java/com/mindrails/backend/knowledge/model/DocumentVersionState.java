package com.mindrails.backend.knowledge.model;

public enum DocumentVersionState {
    QUEUED,
    PARSING,
    CHUNKING,
    EMBEDDING,
    READY,
    FAILED,
    CANCELLED,
    SUPERSEDED,
    ARCHIVED
}
