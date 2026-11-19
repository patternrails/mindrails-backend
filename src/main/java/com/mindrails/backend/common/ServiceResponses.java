package com.mindrails.backend.common;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Converts service results to HTTP responses for controllers. */
public final class ServiceResponses {

    private ServiceResponses() {}

    public static <T> ResponseEntity<T> response(ServiceResult<T> result, HttpStatus successStatus) {
        return switch (result) {
            case ServiceResult.Success<T> success -> ResponseEntity.status(successStatus).body(success.value());
            case ServiceResult.Failure<T> failure -> throw exception(failure);
        };
    }

    public static ServiceResponseException exception(ServiceResult.Failure<?> failure) {
        HttpStatus status = switch (failure.code()) {
            case INVALID_ARGUMENT -> HttpStatus.BAD_REQUEST;
            case NOT_FOUND -> HttpStatus.NOT_FOUND;
            case CONFLICT -> HttpStatus.CONFLICT;
            case NOT_IMPLEMENTED -> HttpStatus.NOT_IMPLEMENTED;
            case KNOWLEDGE_UNAVAILABLE, UNAVAILABLE -> HttpStatus.SERVICE_UNAVAILABLE;
            case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
        };
        return new ServiceResponseException(status, failure.code(), failure.message());
    }
}
