package com.mindrails.backend.knowledge.service.internal;

import com.mindrails.backend.knowledge.service.KnowledgeException;
import com.mindrails.backend.common.ExceptionDiagnostics;

import java.util.concurrent.CancellationException;

record KnowledgeTaskFailure(boolean retryable, String message) {
    static KnowledgeTaskFailure from(Exception error) {
        return switch (error) {
            case KnowledgeException expected ->
                    new KnowledgeTaskFailure(false, ExceptionDiagnostics.summary(expected));
            case CancellationException cancelled ->
                    new KnowledgeTaskFailure(false, "Task cancelled or superseded");
            default ->
                    new KnowledgeTaskFailure(
                            true,
                            "Document processing failed: " + ExceptionDiagnostics.summary(error));
        };
    }
}
