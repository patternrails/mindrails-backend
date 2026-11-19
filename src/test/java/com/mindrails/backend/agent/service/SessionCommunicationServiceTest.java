package com.mindrails.backend.agent.service;

import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.service.internal.LoopSessionStreamService;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionCommunicationServiceTest {
    private final SessionRegistry registry = mock(SessionRegistry.class);
    private final LoopSessionStreamService loops = mock(LoopSessionStreamService.class);
    private final Session source = Session.builder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID())
            .ownerId(UUID.randomUUID()).build();
    private final Session target = source.toBuilder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID()).build();
    private final InvocationParameters parameters = LlmClient.parameters(source, UUID.randomUUID(), "WEBSOCKET");
    private final InvocationContext context = InvocationContext.builder().invocationParameters(parameters).build();
    private final SessionCommunicationService service;

    @SuppressWarnings("unchecked") SessionCommunicationServiceTest() {
        ObjectProvider<LoopSessionStreamService> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(loops);
        service = new SessionCommunicationService(registry, provider);
        parameters.put(LlmClient.COMMUNICATION_CALLS, new SessionCommunicationService.Calls());
        when(registry.findManaged(source.getId())).thenReturn(Optional.of(source));
        when(registry.findManaged(target.getId())).thenReturn(Optional.of(target));
        when(loops.enqueue(eq(target.getId()), any(SessionInput.class))).thenReturn(ServiceResult.success());
    }

    @Test void concurrentDuplicateCallsEnqueueOnceAndKeepServerProvenance() throws Exception {
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var tasks = java.util.stream.IntStream.range(0, 20).mapToObj(i -> executor.submit(() -> send("call", "hello", null))).toList();
            var first = tasks.getFirst().get(5, TimeUnit.SECONDS);
            for (var task : tasks) assertEquals(first, task.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ServiceResult.Success.class, first);
        }
        var input = org.mockito.ArgumentCaptor.forClass(SessionInput.class);
        verify(loops).enqueue(eq(target.getId()), input.capture());
        assertEquals(source.getId(), input.getValue().communication().sourceSessionId());
        assertEquals(parameters.get(LlmClient.RUN_ID), input.getValue().communication().sourceRunId());
        assertEquals(1, input.getValue().communication().hops());
        assertEquals("hello", input.getValue().message());
        assertFailure(ErrorCode.CONFLICT, send("call", "changed", null));
    }

    @Test void rechecksOwnershipAndExistenceEvenForACachedReceipt() {
        assertInstanceOf(ServiceResult.Success.class, send("call", "hello", null));
        target.setOwnerId(UUID.randomUUID());
        assertFailure(ErrorCode.NOT_FOUND, send("call", "hello", null));
        verify(loops, times(1)).enqueue(any(), any(SessionInput.class));
    }

    @Test void validatesReplyPairAndPropagatesHopLimit() {
        UUID incoming = parameters.get(LlmClient.REQUEST_ID);
        parameters.put(LlmClient.COMMUNICATION_SOURCE, new SessionMessageSource(target.getId(), target.getParentAgentId(),
                UUID.randomUUID(), null, Instant.now(), 7));
        assertInstanceOf(ServiceResult.Success.class, send("valid", "reply", incoming));
        assertFailure(ErrorCode.INVALID_ARGUMENT, send("bad", "reply", UUID.randomUUID()));
        parameters.put(LlmClient.COMMUNICATION_SOURCE, new SessionMessageSource(target.getId(), target.getParentAgentId(),
                UUID.randomUUID(), null, Instant.now(), 8));
        assertFailure(ErrorCode.CONFLICT, send("hop", "reply", incoming));
    }

    @Test void rejectsSelfMissingTaskAndCancelledInvocationsWithoutEnqueuing() {
        assertFailure(ErrorCode.INVALID_ARGUMENT, service.communicate(source.getId(), "self", null, "self", context));
        assertFailure(ErrorCode.INVALID_ARGUMENT, send("blank", " ", null));
        assertFailure(ErrorCode.NOT_FOUND, service.communicate(UUID.randomUUID(), "missing", null, "missing", context));
        parameters.put(LlmClient.TASK_ID, UUID.randomUUID());
        assertFailure(ErrorCode.INVALID_ARGUMENT, send("task", "hello", null));
        parameters.asMap().remove(LlmClient.TASK_ID);
        var cancellation = new CompletableFuture<Void>(); cancellation.complete(null);
        parameters.put(LlmClient.CANCELLATION, cancellation);
        assertThrows(CancellationException.class, () -> send("cancel", "hello", null));
        verifyNoInteractions(loops);
    }

    @Test void targetRejectionIsReturnedRatherThanReportedAsAccepted() {
        when(loops.enqueue(eq(target.getId()), any(SessionInput.class))).thenReturn(ServiceResult.failure(ErrorCode.CONFLICT, "Session is doomed"));
        assertFailure(ErrorCode.CONFLICT, send("doomed", "hello", null));
    }

    private ServiceResult<SessionCommunicationService.Receipt> send(String call, String text, UUID reply) {
        return service.communicate(target.getId(), text, reply, call, context);
    }
    private void assertFailure(ErrorCode code, ServiceResult<?> result) {
        assertEquals(code, assertInstanceOf(ServiceResult.Failure.class, result).code());
    }
}
