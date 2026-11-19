package com.mindrails.backend.mcp.api.web;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;

import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/** Converts malformed HTTP requests; business failures use the shared service response advice. */
@RestControllerAdvice(assignableTypes = McpController.class)
public class McpRequestAdvice {
    @ExceptionHandler({
        HttpMessageNotReadableException.class,
        MethodArgumentNotValidException.class,
        MethodArgumentTypeMismatchException.class,
        MissingServletRequestParameterException.class
    })
    public ResponseEntity<ServiceResult.Failure<Void>> invalid(Exception error) {
        return ResponseEntity.badRequest()
                .body(
                        new ServiceResult.Failure<>(
                                ErrorCode.INVALID_ARGUMENT,
                                "Invalid MCP configuration or request"));
    }
}
