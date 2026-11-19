package com.mindrails.backend.asset.api.web;

import com.mindrails.backend.asset.service.AssetException;
import com.mindrails.backend.common.ExceptionDiagnostics;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import java.util.Map;

/** Asset failures can also originate from chat attachment endpoints. */
@lombok.extern.slf4j.Slf4j
@org.springframework.core.annotation.Order(0)
@RestControllerAdvice
public class AssetExceptionAdvice {
    @ExceptionHandler(AssetException.class)
    public ResponseEntity<Map<String, String>> asset(AssetException error) {
        log.warn("Asset operation failed", ExceptionDiagnostics.forLogging(error));
        int status = switch (error.reason()) {
            case INVALID_ARGUMENT -> 400;
            case NOT_FOUND -> 404;
            case CONFLICT -> 409;
            case NOT_IMPLEMENTED -> 501;
            case UNAVAILABLE -> 503;
            case PREVIEW_TOO_LARGE -> 413;
            case PREVIEW_UNSUPPORTED -> 415;
        };
        return ResponseEntity.status(status)
                .header("Cache-Control", "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(
                        Map.of(
                                "code",
                                "ASSET_ERROR",
                                "message",
                                error.getMessage() == null
                                        ? "Asset operation failed"
                                        : ExceptionDiagnostics.safe(error.getMessage())));
    }

}
