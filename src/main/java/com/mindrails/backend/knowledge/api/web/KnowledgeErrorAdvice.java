package com.mindrails.backend.knowledge.api.web;

import com.mindrails.backend.common.ServiceResponseException;
import com.mindrails.backend.common.ExceptionDiagnostics;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * Keeps business failure reasons independent of transport and preserves the public error contract.
 */
@RestControllerAdvice(
        assignableTypes = {
            KnowledgeBaseController.class,
            KnowledgeDocumentController.class,
            KnowledgeSearchController.class,
            AgentKnowledgeBindingController.class
        })
public class KnowledgeErrorAdvice {
    @ExceptionHandler(ServiceResponseException.class)
    public ResponseEntity<KnowledgeErrorResponse> service(ServiceResponseException error) {
        return ResponseEntity.status(error.status())
                .body(
                        new KnowledgeErrorResponse(
                                error.failure().code().name(), ExceptionDiagnostics.safe(error.getMessage())));
    }

    @ExceptionHandler(KnowledgeException.class)
    public ResponseEntity<KnowledgeErrorResponse> failure(KnowledgeException error) {
        int status =
                switch (error.reason()) {
                    case INVALID_ARGUMENT -> 400;
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    case NOT_IMPLEMENTED -> 501;
                    case KNOWLEDGE_UNAVAILABLE -> 503;
                };
        return ResponseEntity.status(status)
                .body(new KnowledgeErrorResponse(error.code(), ExceptionDiagnostics.summary(error)));
    }

    @ExceptionHandler({IllegalArgumentException.class, HttpMessageNotReadableException.class})
    public ResponseEntity<KnowledgeErrorResponse> invalid(Exception error) {
        return ResponseEntity.badRequest()
                .body(new KnowledgeErrorResponse("INVALID_ARGUMENT",
                        error instanceof HttpMessageNotReadableException ? "Invalid knowledge request"
                                : "Invalid knowledge request: " + ExceptionDiagnostics.summary(error)));
    }

    @ExceptionHandler({DataAccessException.class, IllegalStateException.class})
    public ResponseEntity<KnowledgeErrorResponse> unavailable(Exception error) {
        return ResponseEntity.status(503)
                .body(
                        new KnowledgeErrorResponse(
                                "KNOWLEDGE_UNAVAILABLE",
                                "Knowledge storage or embedding service is unavailable: "
                                        + ExceptionDiagnostics.summary(error)));
    }

    @ExceptionHandler({RuntimeException.class, java.io.IOException.class})
    public ResponseEntity<KnowledgeErrorResponse> unexpected(Exception error) {
        return ResponseEntity.internalServerError().body(new KnowledgeErrorResponse(
                "INTERNAL_ERROR", "Knowledge operation failed: " + ExceptionDiagnostics.summary(error)));
    }
}
