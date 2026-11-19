package com.mindrails.backend.metrics;

import com.mindrails.backend.agent.service.internal.TestSessionChat;

import com.mindrails.backend.history.TestHistory;

import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.agent.api.websocket.protocol.*;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.agent.service.internal.*;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.*;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.*;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.*;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.*;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class MetricsChatTest {
    static ChatResponse response(String answer, TokenUsage usage) {
        return ChatResponse.builder().aiMessage(AiMessage.from(answer)).tokenUsage(usage).finishReason(FinishReason.STOP).build();
    }
    static class Fixture implements AutoCloseable {
        final InMemoryMetricsRepository repository = new InMemoryMetricsRepository(new MetricsProperties(), new MetricsHealth());
        final TestMetrics metrics;
        final SessionRegistry sessions = new SessionRegistryConfiguration().sessionRegistry();

        final Session session;
        final DefaultLoopSessionStreamService stream;
        Fixture(ChatModel model, StreamingChatModel streaming, List<IdentifiedTool> tools, MetricsRepository store) {
            var properties = new MetricsProperties();
            metrics = new TestMetrics(properties, store == null ? repository : store, new MetricsHealth(), false);
            var provider = Provider.builder().id(UUID.randomUUID()).name("test").modelName("fake-model")
                    .streaming(streaming != null).chatModel(model).streamingChatModel(streaming).build();
            session = Session.builder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID()).memoryId(UUID.randomUUID()).provider(provider).build();
            sessions.insertIfAbsent(session);
            var factory = TestLlmClients.create(tools, InMemoryChatMemoryStore::new, new ChatMemoryProperties(), null, null, null, null, TestHistory.disabled(), metrics.recorder());
            stream = TestSessionChat.service(sessions, factory, TestHistory.disabled(), metrics.recorder());
        }
        List<ChatStreamEvent> start() {
            var events = new java.util.concurrent.CopyOnWriteArrayList<ChatStreamEvent>();
            assertInstanceOf(ServiceResult.Success.class, TestSessionChat.start(stream, session.getId(), new ClientCommand.Chat(UUID.randomUUID(), "hello"), events::add));
            return events;
        }
        List<ChatStreamEvent> stream() {
            var events = start();
            TestSessionChat.awaitTerminal(events);
            return events;
        }
        MetricsSnapshot snapshot() { metrics.flush(); assertEquals(1, repository.snapshots().size()); return repository.snapshots().getFirst(); }
        @Override public void close() { metrics.close(); }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void recordsSingleCallAndReusedSessionWithNoTextRetained(boolean streaming) {
        ChatModel model = new ChatModel() { @Override public ChatResponse doChat(ChatRequest request) { return response("[\"hello\"]", new TokenUsage(3, 4, 7)); } };
        StreamingChatModel stream = new StreamingChatModel() { @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            handler.onPartialResponse("hello"); handler.onCompleteResponse(response("hello", new TokenUsage(3, 4, 7)));
        } };
        try (var f = new Fixture(streaming ? null : model, streaming ? stream : null, List.of(), null)) {
            if (streaming) assertInstanceOf(ChatStreamEvent.Completed.class, f.stream().getLast());
            else assertInstanceOf(ServiceResult.Success.class, TestSessionChat.chat(f.stream, f.session.getId(), "secret input"));
            var run = f.snapshot();
            assertEquals("SUCCEEDED", run.status()); assertEquals(1, run.modelCallCount()); assertEquals(7L, run.knownTotalTokens());
            assertEquals("COMPLETE", run.usageCoverage()); assertEquals(1, run.modelCalls().size()); assertNotNull(run.modelCalls().getFirst().invocationId());
            assertFalse(run.toString().contains("secret input"));
            if (streaming) f.stream(); else TestSessionChat.chat(f.stream, f.session.getId(), "second");
            f.metrics.flush(); assertEquals(2, f.repository.snapshots().size());
            assertEquals(14L, f.repository.snapshots().stream().mapToLong(MetricsSnapshot::knownTotalTokens).sum());
        }
    }
    static class Ping implements IdentifiedTool { public UUID id() { return UUID.randomUUID(); } @Tool("ping") public String ping() { return "pong"; } }
    static class Broken implements IdentifiedTool { public UUID id() { return UUID.randomUUID(); } @Tool("broken") public String broken() { throw new IllegalStateException("private tool failure"); } }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void toolRoundCountsEachModelAndFunctionOnce(boolean streaming) {
        var calls = new AtomicInteger();
        java.util.function.Supplier<ChatResponse> next = () -> calls.incrementAndGet() == 1
                ? ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("same-id").name("ping").arguments("{}").build()))
                    .tokenUsage(new TokenUsage(2, 3, 5)).finishReason(FinishReason.TOOL_EXECUTION).build()
                : response(streaming ? "pong" : "[\"pong\"]", new TokenUsage(4, 5, 9));
        ChatModel model = new ChatModel() { @Override public ChatResponse doChat(ChatRequest request) { return next.get(); } };
        StreamingChatModel stream = new StreamingChatModel() { @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { handler.onCompleteResponse(next.get()); } };
        try (var f = new Fixture(streaming ? null : model, streaming ? stream : null, List.of(new Ping()), null)) {
            if (streaming) f.stream(); else TestSessionChat.chat(f.stream, f.session.getId(), "hello");
            var run = f.snapshot(); assertEquals("SUCCEEDED", run.status()); assertEquals(2, run.modelCallCount()); assertEquals(14L, run.knownTotalTokens());
            assertEquals(14L, run.aggregateUsageReported().totalTokens()); assertEquals(1, run.functionCallCount());
            assertEquals(1, run.capabilityCalls().size()); assertEquals("SUCCEEDED", run.capabilityCalls().getFirst().status());
            assertEquals(run.modelCalls().getFirst().modelCallId(), run.capabilityCalls().getFirst().modelCallId());
        }
    }
    @Test void toolFailureDoesNotMakeRecoveredRunFail() {
        var calls = new AtomicInteger();
        ChatModel model = new ChatModel() { @Override public ChatResponse doChat(ChatRequest request) {
            return calls.incrementAndGet() == 1 ? ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("broken-id").name("broken").arguments("{}").build())).build()
                    : response("[\"recovered\"]", null);
        } };
        try (var f = new Fixture(model, null, List.of(new Broken()), null)) {
            assertInstanceOf(ServiceResult.Success.class, TestSessionChat.chat(f.stream, f.session.getId(), "hello"));
            var run = f.snapshot(); assertEquals("SUCCEEDED", run.status()); assertEquals(1, run.capabilityFailedCount());
            assertEquals("FAILED", run.capabilityCalls().getFirst().status()); assertNull(run.knownTotalTokens()); assertEquals("UNKNOWN", run.usageCoverage());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void modelFailureRetainsUnknownUsage(boolean streaming) {
        ChatModel model = new ChatModel() { @Override public ChatResponse doChat(ChatRequest request) { throw new IllegalStateException("private provider response"); } };
        StreamingChatModel stream = new StreamingChatModel() { @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { handler.onError(new IllegalStateException("private provider response")); } };
        try (var f = new Fixture(streaming ? null : model, streaming ? stream : null, List.of(), null)) {
            if (streaming) assertInstanceOf(ChatStreamEvent.Failed.class, f.stream().getLast());
            else assertInstanceOf(ServiceResult.Failure.class, TestSessionChat.chat(f.stream, f.session.getId(), "hello"));
            var run = f.snapshot(); assertEquals("FAILED", run.status()); assertEquals(1, run.modelFailedCount()); assertNull(run.knownTotalTokens());
            assertFalse(run.toString().contains("private provider response"));
        }
    }
    @Test void cancellationAndLateResponseStayWithOriginalRun() {
        var handlers = new ArrayList<StreamingChatResponseHandler>();
        StreamingChatModel model = new StreamingChatModel() { @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { handlers.add(handler); } };
        try (var f = new Fixture(null, model, List.of(), null)) {
            f.start(); TestSessionChat.await(() -> handlers.size() == 1); assertInstanceOf(ServiceResult.Success.class, f.stream.stop(f.session.getId()));
            var currentEvents = f.start();
            TestSessionChat.await(() -> handlers.size() == 2);
            handlers.getFirst().onCompleteResponse(response("late", new TokenUsage(10, 20, 30)));
            handlers.getFirst().onCompleteResponse(response("duplicate", new TokenUsage(10, 20, 30)));
            handlers.getLast().onCompleteResponse(response("new", new TokenUsage(2, 3, 5)));
            TestSessionChat.awaitTerminal(currentEvents);
            f.metrics.flush();
            assertEquals(2, f.repository.snapshots().size());
            var cancelled = f.repository.snapshots().stream().filter(run -> run.status().equals("CANCELLED")).findFirst().orElseThrow();
            var succeeded = f.repository.snapshots().stream().filter(run -> run.status().equals("SUCCEEDED")).findFirst().orElseThrow();
            assertEquals(30L, cancelled.knownTotalTokens()); assertEquals(1, cancelled.modelCallCount()); assertEquals(5L, succeeded.knownTotalTokens());
        }
    }
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void brokenStatisticsStoreNeverChangesChatResultOrLease(boolean streaming) {
        ChatModel model = new ChatModel() { @Override public ChatResponse doChat(ChatRequest request) { return response("[\"ok\"]", null); } };
        StreamingChatModel stream = new StreamingChatModel() { @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { handler.onCompleteResponse(response("ok", null)); } };
        try (var f = new Fixture(streaming ? null : model, streaming ? stream : null, List.of(), run -> { throw new IllegalStateException("missing metrics tables"); })) {
            if (streaming) assertInstanceOf(ChatStreamEvent.Completed.class, f.stream().getLast());
            else assertInstanceOf(ServiceResult.Success.class, TestSessionChat.chat(f.stream, f.session.getId(), "hello"));
            f.metrics.flush(); assertEquals(1, f.metrics.health().writeFailures.get());
            if (streaming) assertInstanceOf(ChatStreamEvent.Completed.class, f.stream().getLast());
            else assertInstanceOf(ServiceResult.Success.class, TestSessionChat.chat(f.stream, f.session.getId(), "second"));
        }
    }
    @Test void rejectedMessagesAreNotTurnsAndPreparationFailureIsATurn() {
        ChatModel model = new ChatModel() { @Override public ChatResponse doChat(ChatRequest request) { return response("[\"ok\"]", null); } };
        try (var f = new Fixture(model, null, List.of(), null)) {
            TestSessionChat.chat(f.stream, UUID.randomUUID(), "missing");
            assertEquals(0, f.metrics.buffer().size());
            f.sessions.save(f.session.toBuilder().provider(Provider.builder().streaming(false).build()).build());
            assertInstanceOf(ServiceResult.Failure.class, TestSessionChat.chat(f.stream, f.session.getId(), "hello"));
            assertEquals(0, f.metrics.buffer().size());
        }
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void requestAndResponseRecordsCanBeFlushedSeparatelyAcrossToolRounds(boolean streaming) {
        var metrics = new AtomicReference<TestMetrics>();
        var calls = new AtomicInteger();
        java.util.function.Supplier<ChatResponse> next = () -> {
            metrics.get().flush();
            return calls.getAndIncrement() == 0
                    ? ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                            .id("ping-id").name("ping").arguments("{}").build()))
                            .tokenUsage(new TokenUsage(2, 3, 5)).build()
                    : response(streaming ? "done" : "[\"done\"]", new TokenUsage(4, 5, 9));
        };
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) { return next.get(); }
        };
        StreamingChatModel stream = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(next.get());
            }
        };
        try (var f = new Fixture(streaming ? null : model, streaming ? stream : null, List.of(new Ping()), null)) {
            metrics.set(f.metrics);
            if (streaming) f.stream(); else TestSessionChat.chat(f.stream, f.session.getId(), "hello");
            var run = f.snapshot();
            assertEquals("SUCCEEDED", run.status());
            assertEquals(2, run.modelCallCount());
            assertEquals(14L, run.knownTotalTokens());
            assertTrue(run.modelCalls().stream().allMatch(call -> call.modelCallId() != null && call.durationMs() != null));
            assertEquals(run.modelCalls().getFirst().modelCallId(), run.capabilityCalls().getFirst().modelCallId());
            assertEquals(0, f.metrics.health().writeFailures.get());
        }
    }

    @Test
    void modelFailureCanCompleteAPreviouslyFlushedRequest() {
        var metrics = new AtomicReference<TestMetrics>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                metrics.get().flush();
                throw new IllegalStateException("provider unavailable");
            }
        };
        try (var f = new Fixture(model, null, List.of(), null)) {
            metrics.set(f.metrics);
            assertInstanceOf(ServiceResult.Failure.class, TestSessionChat.chat(f.stream, f.session.getId(), "hello"));
            var run = f.snapshot();
            assertEquals(1, run.modelCallCount());
            assertEquals(1, run.modelFailedCount());
            assertNotNull(run.modelCalls().getFirst().durationMs());
        }
    }
}
