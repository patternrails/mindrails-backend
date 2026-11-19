package com.mindrails.backend.agent.service.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.EventSink;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.asset.service.AssetConversationRetriever;
import com.mindrails.backend.asset.service.AssetException;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.metrics.MetricsRecorder;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.service.TokenStream;
import java.util.Optional;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

class DefaultLoopSessionStreamServiceTest {
    private final SessionRegistry sessions = mock(SessionRegistry.class);
    private final LlmClientFactory factory = mock(LlmClientFactory.class);
    private final AssetConversationRetriever assets = mock(AssetConversationRetriever.class);
    private final DefaultLoopSessionStreamService service = new DefaultLoopSessionStreamService(
            sessions, factory, mock(ChatHistory.class), mock(MetricsRecorder.class),
            assets);
    private final UUID sessionId = UUID.randomUUID();
    private final Session session = Session.builder().id(sessionId).memoryId(UUID.randomUUID())
            .provider(com.mindrails.backend.provider.Provider.builder().build()).parentAgentId(UUID.randomUUID()).ownerId(UUID.randomUUID()).build();
    private final EventSink sink = mock(EventSink.class);

    @AfterEach
    void shutdown() {
        service.shutdown();
    }

    @Test
    void reusesClientForTheSameSession() {
        when(sessions.findManaged(sessionId)).thenReturn(Optional.of(session));
        when(factory.createClientForLoop(session)).thenReturn(ServiceResult.success(mock(LlmClient.class)));

        service.bind(sessionId, sink);
        service.stop(sessionId);
        service.unbind(sessionId, sink);

        verify(sessions).findManaged(sessionId);
        verify(factory).createClientForLoop(session);
    }

    @Test
    void preservesCreationFailureAndAllowsAnotherAttempt() {
        when(sessions.findManaged(sessionId)).thenReturn(Optional.of(session));
        var failure = ServiceResult.<LlmClient>failure(ErrorCode.UNAVAILABLE, "Provider unavailable");
        when(factory.createClientForLoop(session))
                .thenReturn(failure)
                .thenReturn(ServiceResult.success(mock(LlmClient.class)));

        assertEquals(failure, service.bind(sessionId, sink));
        service.bind(sessionId, sink);
        service.stop(sessionId);

        verify(factory, times(2)).createClientForLoop(session);
    }

    @Test
    void missingSessionDoesNotCreateAClient() {
        when(sessions.findManaged(sessionId)).thenReturn(Optional.empty());

        assertEquals(ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found"),
                service.stop(sessionId));
        verifyNoInteractions(factory);
    }

    @Test
    void attachesBeforeCreatingTheLoopAndAlsoAttachesForLaterMessages() {
        when(sessions.findManaged(sessionId)).thenReturn(Optional.of(session));
        var client = mock(LlmClient.class);
        when(factory.createClientForLoop(session)).thenReturn(ServiceResult.success(client));
        when(client.chatWithStream(any(UUID.class), anyString(), any(InvocationParameters.class)))
                .thenReturn(mock(TokenStream.class, RETURNS_SELF));
        var firstAssets = List.of(UUID.randomUUID());
        var secondAssets = List.of(UUID.randomUUID());

        assertEquals(ServiceResult.success(), service.enqueue(sessionId,
                new ClientCommand.Chat(UUID.randomUUID(), "First", firstAssets)));
        assertEquals(ServiceResult.success(), service.enqueue(sessionId,
                new ClientCommand.Chat(UUID.randomUUID(), "Second", secondAssets)));

        var calls = inOrder(assets, factory);
        calls.verify(assets).attach(session.getParentAgentId(), sessionId, firstAssets);
        calls.verify(factory, calls(1)).createClientForLoop(session);
        calls.verify(assets).attach(session.getParentAgentId(), sessionId, secondAssets);
        verify(factory, atLeastOnce()).createClientForLoop(session);
    }

    @Test
    void attachmentFailurePreventsLoopCreation() {
        when(sessions.findManaged(sessionId)).thenReturn(Optional.of(session));
        var versions = List.of(UUID.randomUUID());
        doThrow(AssetException.conflict("Attachment is not ready"))
                .when(assets).attach(session.getParentAgentId(), sessionId, versions);

        assertEquals(ServiceResult.failure(ErrorCode.CONFLICT, "Attachment is not ready"),
                service.enqueue(sessionId, new ClientCommand.Chat(UUID.randomUUID(), "Hello", versions)));
        verifyNoInteractions(factory);
    }

    @Test
    void attachmentsAreRejectedWhenAssetStorageIsDisabled() {
        when(sessions.findManaged(sessionId)).thenReturn(Optional.of(session));
        var withoutAssets = new DefaultLoopSessionStreamService(
                sessions, factory, mock(ChatHistory.class), mock(MetricsRecorder.class), null);
        try {
            assertEquals(ServiceResult.failure(ErrorCode.NOT_IMPLEMENTED, "Asset Storage is disabled"),
                    withoutAssets.enqueue(sessionId, new ClientCommand.Chat(
                            UUID.randomUUID(), "Hello", List.of(UUID.randomUUID()))));
            verifyNoInteractions(factory);
        } finally {
            withoutAssets.shutdown();
        }
    }

    @Test
    void drainsQueuedMessagesAndRestartsWhenAnotherMessageArrives() throws Exception {
        when(sessions.findManaged(sessionId)).thenReturn(Optional.of(session));
        var client = mock(LlmClient.class);
        var tokenStream = mock(TokenStream.class, RETURNS_SELF);
        when(factory.createClientForLoop(session)).thenReturn(ServiceResult.success(client));
        when(client.chatWithStream(any(UUID.class), anyString(), any(InvocationParameters.class)))
                .thenReturn(tokenStream);
        var completion = new java.util.concurrent.atomic.AtomicReference<Consumer<ChatResponse>>();
        doAnswer(call -> { completion.set(call.getArgument(0)); return tokenStream; })
                .when(tokenStream).onCompleteResponse(any());
        doAnswer(call -> {
            completion.get().accept(ChatResponse.builder().aiMessage(AiMessage.from("Done")).build());
            return null;
        }).when(tokenStream).start();
        var first = new ClientCommand.Chat(UUID.randomUUID(), "First");
        var second = new ClientCommand.Chat(UUID.randomUUID(), "Second");
        var executor = (ThreadPoolExecutor) ReflectionTestUtils.getField(service, "runLoopsExecutor");
        executor.setCorePoolSize(1);
        executor.setMaximumPoolSize(1);
        var releaseWorker = new CountDownLatch(1);
        executor.submit(() -> releaseWorker.await(5, TimeUnit.SECONDS));

        Object loop;
        Queue<?> messages;
        try {
            assertEquals(ServiceResult.success(), service.enqueue(sessionId, first));
            assertEquals(ServiceResult.success(), service.enqueue(sessionId, second));

            var loops = (Map<?, ?>) ReflectionTestUtils.getField(service, "llmRunLoops");
            loop = loops.get(sessionId);
            messages = (Queue<?>) ReflectionTestUtils.getField(loop, "messageQueue");
            var queued = List.copyOf(messages);
            assertEquals(List.of("First", "Second"), queued.stream()
                    .map(message -> ReflectionTestUtils.getField(message, "userMessage")).toList());
            assertEquals(session.getMemoryId(), ReflectionTestUtils.getField(queued.get(0), "memoryId"));
            assertEquals(session.getMemoryId(), ReflectionTestUtils.getField(queued.get(1), "memoryId"));
            var firstParameters = (InvocationParameters) ReflectionTestUtils.getField(queued.get(0), "parameters");
            var secondParameters = (InvocationParameters) ReflectionTestUtils.getField(queued.get(1), "parameters");
            assertEquals(first.requestId(), firstParameters.get(LlmClient.REQUEST_ID));
            assertEquals(second.requestId(), secondParameters.get(LlmClient.REQUEST_ID));
            assertEquals(sessionId, firstParameters.get(LlmClient.SESSION_ID));
            assertEquals(session.getMemoryId(), firstParameters.get(LlmClient.MEMORY_ID));
            assertEquals(session.getParentAgentId(), firstParameters.get(LlmClient.AGENT_ID));
            assertEquals(session.getOwnerId(), firstParameters.get(LlmClient.OWNER_ID));
            assertEquals("WEBSOCKET", firstParameters.get(LlmClient.TRANSPORT));
            assertNotNull(firstParameters.get(LlmClient.STARTED_AT));
            assertNotEquals((UUID) firstParameters.get(LlmClient.RUN_ID), secondParameters.get(LlmClient.RUN_ID));
            assertEquals(2, executor.getTaskCount()); // One blocker and one session loop.
        } finally {
            releaseWorker.countDown();
        }
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertTrue(messages.isEmpty());
        assertEquals("IDLE", ReflectionTestUtils.getField(loop, "state").toString());

        when(sessions.findManaged(sessionId)).thenReturn(Optional.of(session.toBuilder()
                .name("Renamed").updatedAt(java.time.Instant.now()).version(2).build()));
        assertEquals(ServiceResult.success(),
                service.enqueue(sessionId, new ClientCommand.Chat(UUID.randomUUID(), "Third")));
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertTrue(messages.isEmpty());
        assertEquals("IDLE", ReflectionTestUtils.getField(loop, "state").toString());
        verify(factory).createClientForLoop(session);
        var calls = inOrder(client, tokenStream);
        var parametersCaptor = ArgumentCaptor.forClass(InvocationParameters.class);
        for (var text : List.of("First", "Second", "Third")) {
            calls.verify(client).chatWithStream(eq(session.getMemoryId()), eq(text), parametersCaptor.capture());
            calls.verify(tokenStream).start();
        }
        var invocations = parametersCaptor.getAllValues();
        CompletableFuture<Void> firstCancellation = invocations.get(0).get(LlmClient.CANCELLATION);
        CompletableFuture<Void> secondCancellation = invocations.get(1).get(LlmClient.CANCELLATION);
        CompletableFuture<Void> thirdCancellation = invocations.get(2).get(LlmClient.CANCELLATION);
        assertNotSame(firstCancellation, secondCancellation);
        assertNotSame(firstCancellation, thirdCancellation);
        assertNotSame(secondCancellation, thirdCancellation);
        assertTrue(firstCancellation.isDone());
        assertTrue(secondCancellation.isDone());
        assertTrue(thirdCancellation.isDone());
    }

    @Test
    @SuppressWarnings("unchecked")
    void bindingIsIdempotentAndUnbindingOnlyRemovesTheSelectedSink() {
        when(sessions.findManaged(sessionId)).thenReturn(Optional.of(session));
        var client = mock(LlmClient.class);
        var stream = mock(TokenStream.class, RETURNS_SELF);
        when(factory.createClientForLoop(session)).thenReturn(ServiceResult.success(client));
        when(client.chatWithStream(any(UUID.class), anyString(), any(InvocationParameters.class))).thenReturn(stream);
        var otherSink = mock(EventSink.class);

        assertEquals(ServiceResult.success(), service.bind(sessionId, sink));
        assertEquals(ServiceResult.success(), service.bind(sessionId, sink));
        assertEquals(ServiceResult.success(), service.bind(sessionId, otherSink));
        var requestId = UUID.randomUUID();
        assertEquals(ServiceResult.success(), service.enqueue(sessionId, new ClientCommand.Chat(requestId, "Hello")));
        verify(stream, timeout(5000)).start();
        ArgumentCaptor<Consumer<List<Content>>> callback = ArgumentCaptor.forClass(Consumer.class);
        verify(stream).onRetrieved(callback.capture());
        var contents = List.of(Content.from("Source"));
        var event = new ChatStreamEvent.Retrieved(requestId,
                List.of(new ChatStreamEvent.Source(null, null, "Source", null)));

        callback.getValue().accept(contents);
        verify(sink).emit(event);
        verify(otherSink).emit(event);
        clearInvocations(sink, otherSink);

        assertEquals(ServiceResult.success(), service.unbind(sessionId, sink));
        assertEquals(ServiceResult.success(), service.unbind(sessionId, sink));
        callback.getValue().accept(contents);
        verifyNoInteractions(sink);
        verify(otherSink).emit(event);
        clearInvocations(otherSink);

        assertEquals(ServiceResult.success(), service.unbind(sessionId, otherSink));
        callback.getValue().accept(contents);
        verifyNoInteractions(sink, otherSink);
    }

    @Test
    void rejectsMessagesInDoomedState() {
        when(sessions.findManaged(sessionId)).thenReturn(Optional.of(session));
        when(factory.createClientForLoop(session)).thenReturn(ServiceResult.success(mock(LlmClient.class)));
        service.bind(sessionId, sink);
        var loops = (Map<?, ?>) ReflectionTestUtils.getField(service, "llmRunLoops");
        var loop = loops.get(sessionId);
        var state = (Enum<?>) ReflectionTestUtils.getField(loop, "state");
        var doomed = java.util.Arrays.stream(state.getDeclaringClass().getEnumConstants())
                .filter(value -> value.name().equals("DOOMED")).findFirst().orElseThrow();
        ReflectionTestUtils.setField(loop, "state", doomed);

        assertEquals(ServiceResult.failure(ErrorCode.CONFLICT, "Session is doomed"),
                service.enqueue(sessionId, new ClientCommand.Chat(UUID.randomUUID(), "Hello")));

        var messages = (Queue<?>) ReflectionTestUtils.getField(loop, "messageQueue");
        assertTrue(messages.isEmpty());
    }

    @Test
    void schedulingFailureMarksTheLoopDoomed() {
        when(sessions.findManaged(sessionId)).thenReturn(Optional.of(session));
        when(factory.createClientForLoop(session)).thenReturn(ServiceResult.success(mock(LlmClient.class)));
        service.shutdown();
        var message = new ClientCommand.Chat(UUID.randomUUID(), "Hello");
        var failure = ServiceResult.failure(ErrorCode.UNAVAILABLE, "Session loop executor is unavailable");

        assertEquals(failure, service.enqueue(sessionId, message));
        assertEquals(ServiceResult.failure(ErrorCode.CONFLICT, "Session is doomed"),
                service.enqueue(sessionId, message));
    }
}
