package com.mindrails.backend.common;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Returns typed error bodies independently of each controller's success payload type. */
@RestControllerAdvice
public class ServiceResponseAdvice {

    @ExceptionHandler(ServiceResponseException.class)
    public ResponseEntity<ServiceResult.Failure<Void>> handle(ServiceResponseException exception) {
        return ResponseEntity.status(exception.status()).body(exception.failure());
    }
}
