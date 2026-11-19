package com.mindrails.backend.history.api.web;

import com.mindrails.backend.common.ServiceResponseException;

import org.springframework.dao.DataAccessException;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

@RestControllerAdvice(assignableTypes = HistoryController.class)
public class HistoryErrorAdvice {
    @ExceptionHandler(ServiceResponseException.class)
    public ResponseEntity<HistoryErrorResponse> service(ServiceResponseException error) {
        return ResponseEntity.status(error.status())
                .body(new HistoryErrorResponse(error.failure().code().name(), error.getMessage()));
    }

    @ExceptionHandler({IllegalArgumentException.class, MethodArgumentTypeMismatchException.class})
    public ResponseEntity<HistoryErrorResponse> invalid(Exception error) {
        return ResponseEntity.badRequest()
                .body(new HistoryErrorResponse("INVALID_ARGUMENT", "Invalid query parameters"));
    }

    @ExceptionHandler({DataAccessException.class, TransactionException.class})
    public ResponseEntity<HistoryErrorResponse> unavailable(Exception error) {
        return ResponseEntity.status(503)
                .body(new HistoryErrorResponse("UNAVAILABLE", "Archive storage is unavailable"));
    }
}
