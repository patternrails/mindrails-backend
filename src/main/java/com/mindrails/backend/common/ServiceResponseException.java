package com.mindrails.backend.common;

import org.springframework.http.HttpStatus;

/** HTTP-boundary signal; service implementations continue to return failure results. */
public final class ServiceResponseException extends RuntimeException {

    private final HttpStatus status;
    private final ServiceResult.Failure<Void> failure;

    public ServiceResponseException(HttpStatus status, ServiceResult.ErrorCode code, String message) {
        super(message);
        this.status = status;
        this.failure = new ServiceResult.Failure<>(code, message);
    }

    public HttpStatus status() {
        return status;
    }

    public ServiceResult.Failure<Void> failure() {
        return failure;
    }
}
