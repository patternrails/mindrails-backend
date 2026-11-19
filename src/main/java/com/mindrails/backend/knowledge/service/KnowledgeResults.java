package com.mindrails.backend.knowledge.service;

import com.mindrails.backend.common.ServiceResult;

import java.util.function.Supplier;

/**
 * Expected business failures use ServiceResult; unexpected infrastructure causes are not discarded.
 */
public final class KnowledgeResults {
    private KnowledgeResults() {}

    public static <T> ServiceResult<T> attempt(Supplier<T> work) {
        try {
            return ServiceResult.success(work.get());
        } catch (KnowledgeException error) {
            if (error.getCause() != null) {
                throw error;
            }
            return ServiceResult.failure(
                    ServiceResult.ErrorCode.valueOf(error.code()), error.getMessage());
        }
    }

    public static <T> T require(ServiceResult<T> result) {
        return switch (result) {
            case ServiceResult.Success<T> success -> success.value();
            case ServiceResult.Failure<T> failure ->
                    throw new KnowledgeException(
                            KnowledgeException.Reason.valueOf(failure.code().name()),
                            failure.message());
        };
    }
}
