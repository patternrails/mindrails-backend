package com.mindrails.backend.image;

import com.mindrails.backend.common.ServiceResult;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
public final class ImageGenerationAdvice {
    @ExceptionHandler(ImageGenerationException.class)
    public ResponseEntity<ServiceResult.Failure<Void>> error(ImageGenerationException error) {
        HttpStatus status = switch (error.code()) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_IMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
            default -> HttpStatus.SERVICE_UNAVAILABLE;
        };
        return ResponseEntity.status(status).body(new ServiceResult.Failure<>(error.code(), error.getMessage()));
    }
}
