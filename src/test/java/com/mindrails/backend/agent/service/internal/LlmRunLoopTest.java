package com.mindrails.backend.agent.service.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService.MessageToLLM;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.metrics.MetricsRecorder;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class LlmRunLoopTest {
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final LlmClient client = mock(LlmClient.class);
    private final Session session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
            .ownerId(UUID.randomUUID()).parentAgentId(UUID.randomUUID()).build();
    private final LlmRunLoop loop = new LlmRunLoop(client, executor, mock(ChatHistory.class), mock(MetricsRecorder.class));
    private final BlockingQueue<Stream> started = new LinkedBlockingQueue<>();
    private final List<ChatStreamEvent> events = new CopyOnWriteArrayList<>();

    LlmRunLoopTest() {
        loop.registerEventSink(events::add);
        when(client.chatWithStream(any(), anyString(), any())).thenAnswer(call -> {
            var stream = new Stream(call.getArgument(1), call.getArgument(2));
            return stream.tokens;
        });
    }

    @AfterEach
    void shutdown() throws Exception {
        loop.stop();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }

    @Test
    void publishesActualRuntimeStateWithoutDependingOnContentSinks() throws Exception {
        var statuses = new CopyOnWriteArrayList<String>();
        var pending = new CopyOnWriteArrayList<Integer>();
        var finished = new CopyOnWriteArrayList<com.mindrails.backend.userstatus.UserSessionStatusService.Finished>();
        loop.observe((state, active, queued, last) -> {
            statuses.add(state); pending.add(queued); if (last != null) finished.add(last);
        });
        var firstInput = message("one");
        loop.enqueue(firstInput); var first = next();
        loop.enqueue(message("two"));
        first.complete(); var second = next();
        assertEquals("running", statuses.getLast());
        assertEquals(firstInput.requestId(), finished.getLast().requestId());
        assertEquals("completed", finished.getLast().outcome());
        assertTrue(pending.contains(1));
        loop.stop(); awaitIdle();
        assertEquals("idle", statuses.getLast());
        assertEquals("cancelled", finished.getLast().outcome());
    }

    @Test
    void statusObserverFailureDoesNotFailModelExecution() throws Exception {
        loop.observe((state, active, queued, last) -> { throw new IllegalStateException("observer failed"); });
        loop.enqueue(message("hello")); next().complete(); awaitIdle();
        assertEquals(1, events.stream().filter(ChatStreamEvent.Completed.class::isInstance).count());
    }

    @Test
    void waitsForEachCompletionBeforeTakingTheNextMessageAndCanRestart() throws Exception {
        var a = message("A");
        var b = message("B");
        loop.enqueue(a);
        var first = next();
        loop.enqueue(b);
        loop.enqueue(message("C"));
        verify(client, times(1)).chatWithStream(any(), anyString(), any());
        assertFalse(first.cancellation().isDone());
        first.complete();
        var second = next();
        assertEquals("B", second.text);
        assertNotSame(first.cancellation(), second.cancellation());
        assertTrue(first.cancellation().isDone());
        assertFalse(second.cancellation().isDone());
        second.complete();
        var third = next();
        assertEquals("C", third.text);
        third.complete();
        awaitIdle();
        loop.enqueue(message("D"));
        var fourth = next();
        assertEquals("D", fourth.text);
        fourth.complete();
        awaitIdle();
        assertEquals(4, events.stream().filter(ChatStreamEvent.Completed.class::isInstance).count());
    }

    @Test
    void stopConsumesPendingWorkAndLateCallbacksCannotFinishTheNextMessage() throws Exception {
        var memory = MessageWindowChatMemory.withMaxMessages(100);
        when(client.getChatMemory(dev.langchain4j.service.memory.ChatMemoryService.DEFAULT)).thenReturn(memory);
        var a = message("A");
        var b = message("B");
        var c = message("C");
        loop.enqueue(a);
        var first = next();
        var handle = mock(StreamingHandle.class);
        ReflectionTestUtils.invokeMethod(loop, "recordHandle", a, handle);
        loop.enqueue(b);
        loop.enqueue(c);
        assertEquals(ServiceResult.success(), loop.stop());
        verify(handle).cancel();
        assertTrue(first.cancellation().isDone());
        assertEquals(List.of(a.requestId(), b.requestId(), c.requestId()), events.stream()
                .filter(ChatStreamEvent.Cancelled.class::isInstance).map(ChatStreamEvent::requestId).toList());
        loop.enqueue(message("D"));
        var next = next();
        assertEquals("D", next.text);
        first.complete();
        first.error.accept(new IllegalStateException("Late old failure"));
        assertFalse(next.cancellation().isDone());
        assertTrue(events.stream().noneMatch(ChatStreamEvent.Failed.class::isInstance));
        var texts = memory.messages().stream().map(UserMessage.class::cast).map(UserMessage::singleText).toList();
        assertEquals(List.of("B", "C"), texts.subList(0, 2));
        assertTrue(texts.get(2).contains("stopped"));
        next.complete();
        awaitIdle();
        verify(client, times(2)).chatWithStream(any(), anyString(), any());
    }

    @Test
    void stopBeforeTheWorkerStartsDoesNotExecuteQueuedMessages() throws Exception {
        var release = new CountDownLatch(1);
        executor.submit(() -> { release.await(5, TimeUnit.SECONDS); return null; });
        try {
            loop.enqueue(message("A"));
            loop.enqueue(message("B"));
            loop.stop();
        } finally { release.countDown(); }
        awaitIdle();
        verify(client, never()).chatWithStream(any(), anyString(), any());
        assertEquals(2, events.stream().filter(ChatStreamEvent.Cancelled.class::isInstance).count());
    }

    @Test
    void technicalFailureDropsQueuedWorkWithoutAddingItToMemoryAndAllowsNewWork() throws Exception {
        loop.enqueue(message("A"));
        var first = next();
        loop.enqueue(message("B"));
        loop.enqueue(message("C"));
        first.error.accept(new IllegalStateException("Provider timed out"));
        awaitIdle();
        assertEquals(3, events.stream().filter(ChatStreamEvent.Failed.class::isInstance).count());
        loop.enqueue(message("D"));
        var next = next();
        assertEquals("D", next.text);
        next.complete();
        awaitIdle();
        verify(client, never()).getChatMemory(any());
        verify(client, times(2)).chatWithStream(any(), anyString(), any());
    }

    @Test
    void sinkFailureDoesNotPreventOtherSinksOrFollowingMessages() throws Exception {
        loop.registerEventSink(event -> { throw new IllegalStateException("Disconnected sink"); });
        loop.enqueue(message("A"));
        next().complete();
        awaitIdle();
        loop.enqueue(message("B"));
        next().complete();
        awaitIdle();
        assertEquals(2, events.stream().filter(ChatStreamEvent.Completed.class::isInstance).count());
    }

    @Test
    void workerUsesTheEnqueuingUsersIdentity() throws Exception {
        try (var ignored = ResourceAccess.as(session.getOwnerId())) { loop.enqueue(message("A")); }
        var stream = next();
        assertEquals(session.getOwnerId(), stream.owner);
        stream.complete();
        awaitIdle();
        assertNull(executor.submit(ResourceAccess::userId).get(5, TimeUnit.SECONDS));
    }

    @Test
    void fatalFailureMakesTheSessionUnusable() throws Exception {
        var states = new CopyOnWriteArrayList<String>();
        loop.observe((state, active, queued, last) -> states.add(state));
        loop.enqueue(message("A"));
        next().error.accept(new AssertionError("Broken session invariant"));
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertInstanceOf(ServiceResult.Failure.class, loop.enqueue(message("B")));
        verify(client, times(1)).chatWithStream(any(), anyString(), any());
        assertEquals("doomed", states.getLast());
    }

    private MessageToLLM message(String text) {
        var id = UUID.randomUUID();
        return new MessageToLLM(id, session.getMemoryId(), text, LlmClient.parameters(session, id, "WEBSOCKET"));
    }

    private Stream next() throws Exception {
        var stream = started.poll(5, TimeUnit.SECONDS);
        assertNotNull(stream, "The next message did not start");
        return stream;
    }

    private void awaitIdle() throws Exception {
        executor.submit(() -> {}).get(5, TimeUnit.SECONDS);
        assertEquals("IDLE", ReflectionTestUtils.getField(loop, "state").toString());
    }

    private final class Stream {
        final String text;
        final dev.langchain4j.invocation.InvocationParameters parameters;
        final TokenStream tokens = mock(TokenStream.class, RETURNS_SELF);
        Consumer<ChatResponse> completion;
        Consumer<Throwable> error;
        UUID owner;

        Stream(String text, dev.langchain4j.invocation.InvocationParameters parameters) {
            this.text = text;
            this.parameters = parameters;
            doAnswer(call -> { completion = call.getArgument(0); return tokens; }).when(tokens).onCompleteResponse(any());
            doAnswer(call -> { error = call.getArgument(0); return tokens; }).when(tokens).onError(any());
            doAnswer(call -> { owner = ResourceAccess.userId(); started.add(this); return null; }).when(tokens).start();
        }

        CompletableFuture<Void> cancellation() { return parameters.get(LlmClient.CANCELLATION); }
        void complete() { completion.accept(ChatResponse.builder().aiMessage(AiMessage.from("Done")).build()); }
    }
}
