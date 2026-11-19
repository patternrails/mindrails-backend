package com.mindrails.backend.asset.api.web;

import com.mindrails.backend.knowledge.service.KnowledgeException;
import com.mindrails.backend.common.ExceptionDiagnostics;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/** Error details are bounded and redacted before reaching clients or logs. */
@lombok.extern.slf4j.Slf4j
@RestControllerAdvice(assignableTypes = {AssetStorageController.class, SessionDeliverablesController.class, FilePreviewController.class})
public class AssetErrorAdvice {
    @ExceptionHandler(KnowledgeException.class)
    public ResponseEntity<Map<String, String>> knowledge(KnowledgeException error) {
        logFailure(error);
        int status =
                switch (error.reason()) {
                    case INVALID_ARGUMENT -> 400;
                    case NOT_FOUND -> 404;
                    case CONFLICT -> 409;
                    case NOT_IMPLEMENTED -> 501;
                    case KNOWLEDGE_UNAVAILABLE -> 503;
                };
        return ResponseEntity.status(status)
                .body(
                        Map.of(
                                "code",
                                error.code(),
                                "message",
                                ExceptionDiagnostics.summary(error)));
    }

    @ExceptionHandler({RuntimeException.class, java.io.IOException.class})
    public ResponseEntity<Map<String, String>> unavailable(Exception error) {
        logFailure(error);
        return ResponseEntity.status(503)
                .body(
                        Map.of(
                                "code",
                                "ASSET_UNAVAILABLE",
                                "message",
                                "Asset storage operation failed: " + ExceptionDiagnostics.summary(error)));
    }

    @ExceptionHandler({
        IllegalArgumentException.class,
        org.springframework.http.converter.HttpMessageNotReadableException.class
    })
    public ResponseEntity<Map<String, String>> invalid(Exception error) {
        logFailure(error);
        return ResponseEntity.badRequest()
                .body(Map.of("code", "INVALID_ARGUMENT", "message",
                        error instanceof org.springframework.http.converter.HttpMessageNotReadableException
                                ? "Invalid asset request JSON"
                                : "Invalid asset request: " + ExceptionDiagnostics.summary(error)));
    }

    private void logFailure(Exception error) {
        log.warn("Asset API request failed", ExceptionDiagnostics.forLogging(error));
    }

    @ExceptionHandler(org.springframework.web.multipart.MaxUploadSizeExceededException.class)
    public ResponseEntity<Map<String, String>> tooLarge(Exception error) {
        logFailure(error);
        return ResponseEntity.status(413).body(Map.of("code", "UPLOAD_TOO_LARGE",
                "message", "File exceeds the configured upload size limit"));
    }
}
