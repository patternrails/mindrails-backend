package com.mindrails.backend.common;

import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Explicit success or failure returned by a service, independent of HTTP. */
public sealed interface ServiceResult<T> {

    record Success<T>(T value) implements ServiceResult<T> {}

    record Failure<T>(ErrorCode code, String message) implements ServiceResult<T> {
        public Failure {
            Objects.requireNonNull(code, "code");
        }
    }

    enum ErrorCode {
        INVALID_ARGUMENT,
        NOT_FOUND,
        CONFLICT,
        NOT_IMPLEMENTED,
        KNOWLEDGE_UNAVAILABLE,
        UNAVAILABLE,
        INTERNAL_ERROR
    }

    static <T> ServiceResult<T> success(T value) {
        return new Success<>(value);
    }

    static ServiceResult<Void> success() {
        return new Success<>(null);
    }

    static <T> ServiceResult<T> failure(ErrorCode code, String message) {
        return new Failure<>(code, message);
    }

    /** Executes an operation and converts a RuntimeException into a failure using its message. */
    static <T> ServiceResult<T> tryOf(Supplier<? extends T> operation, ErrorCode code) {
        try {
            return success(operation.get());
        } catch (RuntimeException exception) {
            return failure(code, exception.getMessage());
        }
    }

    default <R> ServiceResult<R> map(Function<? super T, ? extends R> mapper) {
        return switch (this) {
            case Success<T> success -> ServiceResult.success(mapper.apply(success.value()));
            case Failure<T> failure -> ServiceResult.failure(failure.code(), failure.message());
        };
    }

    /** Chains an operation that already returns a ServiceResult; failures skip the operation. */
    default <R> ServiceResult<R> flatMap(Function<? super T, ServiceResult<R>> mapper) {
        return switch (this) {
            case Success<T> success -> mapper.apply(success.value());
            case Failure<T> failure -> ServiceResult.failure(failure.code(), failure.message());
        };
    }

    /** Applies an action to a success value and returns this result; failures skip the action. */
    default ServiceResult<T> peek(Consumer<? super T> action) {
        return switch (this) {
            case Success<T> success -> {
                action.accept(success.value());
                yield success;
            }
            case Failure<T> failure -> failure;
        };
    }

    default ServiceResult<T> onSuccess(Runnable action) {
        return switch (this) {
            case Success<T> success -> {
                action.run();
                yield success;
            }
            case Failure<T> failure -> failure;
        };
    }

    /** Applies an action to a failure and returns this result; successes skip the action. */
    default ServiceResult<T> onFailure(Consumer<? super Failure<T>> action) {
        return switch (this) {
            case Success<T> success -> success;
            case Failure<T> failure -> {
                action.accept(failure);
                yield failure;
            }
        };
    }
}
