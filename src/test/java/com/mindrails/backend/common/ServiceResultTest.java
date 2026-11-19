package com.mindrails.backend.common;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ServiceResultTest {

    @Test
    void tryOfPreservesExceptionMessagesAndContinuesTheFailureChain() {
        for (RuntimeException exception : List.of(new IllegalArgumentException("Invalid request"), new IllegalStateException())) {
            AtomicReference<ServiceResult.Failure<String>> observed = new AtomicReference<>();
            ServiceResult<String> result = ServiceResult.<String>tryOf(() -> {
                throw exception;
            }, ServiceResult.ErrorCode.INVALID_ARGUMENT).map(value -> {
                fail("A failed operation must not invoke map");
                return value;
            }).onFailure(observed::set);

            assertSame(result, observed.get());
            assertEquals(ServiceResult.ErrorCode.INVALID_ARGUMENT, observed.get().code());
            assertEquals(exception.getMessage(), observed.get().message());
        }
    }

    @Test
    void peekConsumesTheSuccessValueOnceAndReturnsTheSameResult() {
        List<String> content = List.of("first", "second");
        ServiceResult<List<String>> result = ServiceResult.success(content);
        AtomicInteger calls = new AtomicInteger();

        ServiceResult<List<String>> returned = result.peek(value -> {
            assertSame(content, value);
            calls.incrementAndGet();
        });

        assertSame(result, returned);
        assertEquals(1, calls.get());
    }

    @Test
    void peekSkipsFailuresAndPreservesTheirIdentityAndDetails() {
        ServiceResult<String> result = ServiceResult.failure(ServiceResult.ErrorCode.NOT_FOUND, "Session not found");

        ServiceResult<String> returned = result.peek(value -> fail("Failures must not invoke the consumer"));

        assertSame(result, returned);
        assertEquals(ServiceResult.ErrorCode.NOT_FOUND, ((ServiceResult.Failure<String>) returned).code());
        assertEquals("Session not found", ((ServiceResult.Failure<String>) returned).message());
    }

    @Test
    void peekRunsBeforeTheFollowingMap() {
        List<String> actions = new ArrayList<>();
        ServiceResult<List<String>> result = ServiceResult.success(List.of("first", "second"));

        ServiceResult<Integer> mapped = result.peek(value -> actions.add("peek: " + value))
                .map(value -> {
                    actions.add("map: " + value);
                    return value.size();
                });

        assertEquals(ServiceResult.success(2), mapped);
        assertEquals(List.of("peek: [first, second]", "map: [first, second]"), actions);
    }

    @Test
    void peekConsumesANullSuccessValue() {
        ServiceResult<Void> result = ServiceResult.success(null);
        AtomicInteger calls = new AtomicInteger();

        assertSame(result, result.peek(value -> {
            assertNull(value);
            calls.incrementAndGet();
        }));

        assertEquals(1, calls.get());
    }
}
