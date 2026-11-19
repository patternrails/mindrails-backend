package com.mindrails.backend.identity;

import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestControllerAdvice
@org.springframework.core.annotation.Order(-10)
public class IdentityErrorAdvice {
    @ExceptionHandler(IdentityException.class)
    public ResponseEntity<Map<String,String>> error(IdentityException e) {
        return ResponseEntity.status(e.status).header("Cache-Control","no-store").body(Map.of("message",e.getMessage()));
    }
}
