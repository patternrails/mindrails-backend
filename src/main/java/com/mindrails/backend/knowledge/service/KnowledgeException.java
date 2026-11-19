package com.mindrails.backend.knowledge.service;

/** Expected knowledge operation failure. HTTP mapping belongs to api/web. */
public final class KnowledgeException extends RuntimeException {
    public enum Reason {
        INVALID_ARGUMENT,
        NOT_FOUND,
        CONFLICT,
        NOT_IMPLEMENTED,
        KNOWLEDGE_UNAVAILABLE
    }

    private final Reason reason;

    public KnowledgeException(Reason reason, String message) {
        super(message);
        this.reason = reason;
    }

    public KnowledgeException(Reason reason, String message, Throwable cause) {
        super(message, cause);
        this.reason = reason;
    }

    public static KnowledgeException invalid(String message, Throwable cause) {
        return new KnowledgeException(Reason.INVALID_ARGUMENT, message, cause);
    }

    public static KnowledgeException unavailable(Throwable cause) {
        return new KnowledgeException(
                Reason.KNOWLEDGE_UNAVAILABLE, "Knowledge storage is unavailable", cause);
    }

    public Reason reason() {
        return reason;
    }

    public String code() {
        return reason.name();
    }

    public static KnowledgeException invalid(String message) {
        return new KnowledgeException(Reason.INVALID_ARGUMENT, message);
    }

    public static KnowledgeException missing() {
        return new KnowledgeException(Reason.NOT_FOUND, "Knowledge resource not found");
    }

    public static KnowledgeException conflict(String message) {
        return new KnowledgeException(Reason.CONFLICT, message);
    }

    public static KnowledgeException disabled() {
        return new KnowledgeException(Reason.NOT_IMPLEMENTED, "Knowledge bases are disabled");
    }

    public static KnowledgeException unavailable() {
        return new KnowledgeException(
                Reason.KNOWLEDGE_UNAVAILABLE, "Knowledge storage is unavailable");
    }
}
