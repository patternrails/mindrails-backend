package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.service.internal.TestSessionChat;

import com.mindrails.backend.metrics.TestMetrics;

import com.mindrails.backend.history.TestHistory;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.PartialResponse;
import dev.langchain4j.model.chat.response.PartialResponseContext;
import dev.langchain4j.model.chat.response.PartialThinking;
import dev.langchain4j.model.chat.response.PartialThinkingContext;
import dev.langchain4j.model.chat.response.PartialToolCall;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.TokenStream;
import dev.langchain4j.service.tool.ToolExecution;
import dev.langchain4j.service.tool.ToolExecutionResult;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class LoopSessionEventsTest {
    private final UUID requestId = UUID.randomUUID();

    @Test
    void truncatedToolArgumentsReachTheToolErrorHandlerWithoutBreakingStreamProjection() {
        var calls = new AtomicInteger();
        var tool = new EchoTool();
        var malformed = ToolExecutionRequest.builder().id("truncated-edit").name("echo")
                .arguments("{\"text\":\"unfinished").build();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                if (calls.incrementAndGet() == 1) {
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(malformed))
                            .finishReason(FinishReason.LENGTH).build());
                } else {
                    var result = assertInstanceOf(dev.langchain4j.data.message.ToolExecutionResultMessage.class,
                            request.messages().getLast());
                    assertEquals(malformed.id(), result.id());
                    assertTrue(result.text().contains("INVALID_ARGUMENT"));
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Arguments were truncated"))
                            .finishReason(FinishReason.STOP).build());
                }
            }
        };
        List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        stream(model, events, List.of(tool));

        assertEquals(2, calls.get());
        assertEquals(0, tool.executions.get());
        assertTrue(events.stream().noneMatch(ChatStreamEvent.Failed.class::isInstance));
        assertInstanceOf(ChatStreamEvent.Completed.class, events.getLast());
        var intermediate = events.stream().filter(ChatStreamEvent.ModelResponseCompleted.class::isInstance)
                .map(ChatStreamEvent.ModelResponseCompleted.class::cast).findFirst().orElseThrow();
        assertEquals(Map.of("preview", "{\"text\":\"unfinished"), intermediate.toolCalls().getFirst().arguments());
    }

    public static class EchoTool implements IdentifiedTool {
        private final UUID id = UUID.randomUUID();
        private final AtomicInteger executions = new AtomicInteger();

        @Override
        public UUID id() { return id; }

        @Tool("Echo text")
        public String echo(String text) {
            executions.incrementAndGet();
            return text;
        }
    }

    @Test
    void startsImmediatelyAndForwardsResponseEvents() {
        AtomicInteger calls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                calls.incrementAndGet();
                StreamingHandle streamingHandle = mock(StreamingHandle.class);
                handler.onPartialThinking(new PartialThinking("Let me think"), new PartialThinkingContext(streamingHandle));
                handler.onPartialResponse(new PartialResponse("Hello"), new PartialResponseContext(streamingHandle));
                verify(streamingHandle, never()).cancel();
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Hello"))
                        .finishReason(FinishReason.STOP).tokenUsage(new TokenUsage(2, 3, 5)).build());
            }
        };
        List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        stream(model, events);

        assertEquals(1, calls.get());
        assertEquals(4, events.size());
        assertInstanceOf(ChatStreamEvent.Started.class, events.get(0));
        var thinking = assertInstanceOf(ChatStreamEvent.ThinkingDelta.class, events.get(1));
        var answer = assertInstanceOf(ChatStreamEvent.AnswerDelta.class, events.get(2));
        var completed = assertInstanceOf(ChatStreamEvent.Completed.class, events.get(3));
        assertEquals(1, answer.round());
        assertEquals("Let me think", thinking.text());
        assertEquals("Hello", answer.text());
        assertEquals(answer.messageId(), thinking.messageId());
        assertEquals(answer.messageId(), completed.finalMessageId());
        assertEquals("Hello", completed.answer());
        assertEquals("STOP", completed.finishReason());
        assertEquals(new ChatStreamEvent.Usage(2L, 3L, 5L), completed.usage());
    }

    @Test
    void forwardsIntermediateToolRequestsAndKeepsTheFinalAnswerInItsOwnRound() {
        AtomicInteger calls = new AtomicInteger();
        ToolExecutionRequest toolRequest = ToolExecutionRequest.builder()
                .id("ping-1").name("ping").arguments("{}").build();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                if (calls.incrementAndGet() == 1) {
                    handler.onPartialResponse("Checking");
                    handler.onPartialToolCall(PartialToolCall.builder().index(0).name("ping")
                            .partialArguments("{").build());
                    handler.onPartialToolCall(PartialToolCall.builder().index(0).id("ping-1").name("ping")
                            .partialArguments("}").build());
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(toolRequest))
                            .finishReason(FinishReason.TOOL_EXECUTION).tokenUsage(new TokenUsage(2, 3, 5)).build());
                } else {
                    handler.onPartialResponse("pong");
                    handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("pong"))
                            .finishReason(FinishReason.STOP).tokenUsage(new TokenUsage(4, 5, 9)).build());
                }
            }
        };
        List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        stream(model, events, List.of(new PingTool()));

        assertEquals(2, calls.get());
        assertEquals(8, events.size());
        var firstAnswer = assertInstanceOf(ChatStreamEvent.AnswerDelta.class, events.get(1));
        var firstArguments = assertInstanceOf(ChatStreamEvent.ToolArgumentsDelta.class, events.get(2));
        var intermediate = assertInstanceOf(ChatStreamEvent.ModelResponseCompleted.class, events.get(3));
        var toolStarted = assertInstanceOf(ChatStreamEvent.ToolStarted.class, events.get(4));
        var toolCompleted = assertInstanceOf(ChatStreamEvent.ToolCompleted.class, events.get(5));
        var finalAnswer = assertInstanceOf(ChatStreamEvent.AnswerDelta.class, events.get(6));
        var completed = assertInstanceOf(ChatStreamEvent.Completed.class, events.get(7));
        assertEquals(1, firstArguments.round());
        assertEquals("ping", firstArguments.toolName());
        assertEquals("}", firstArguments.text());
        assertEquals(1, intermediate.round());
        assertEquals(firstAnswer.messageId(), intermediate.messageId());
        assertEquals("TOOL_EXECUTION", intermediate.finishReason());
        assertEquals(List.of(new ChatStreamEvent.ToolCall(firstArguments.toolCallId(), "ping", Map.of())),
                intermediate.toolCalls());
        assertEquals(firstArguments.toolCallId(), toolStarted.toolCallId());
        assertEquals(firstArguments.toolCallId(), toolCompleted.toolCallId());
        assertEquals(1, toolStarted.round());
        assertEquals(1, toolCompleted.round());
        assertEquals(Map.of(), toolStarted.arguments());
        assertEquals("pong", toolCompleted.result());
        assertNotNull(toolCompleted.durationMs());
        assertTrue(toolCompleted.durationMs() >= 0);
        assertEquals(2, finalAnswer.round());
        assertNotEquals(intermediate.messageId(), finalAnswer.messageId());
        assertEquals(finalAnswer.messageId(), completed.finalMessageId());
        assertEquals("pong", completed.answer());
        assertEquals(new ChatStreamEvent.Usage(6L, 8L, 14L), completed.usage());
    }

    @Test
    void mapsAnErrorToolResultToToolFailedRatherThanToolCompleted() {
        TokenStream stream = mock(TokenStream.class, RETURNS_SELF);
        LlmClient client = mock(LlmClient.class);
        when(client.chatWithStream(any(UUID.class), eq("Hello"), any(dev.langchain4j.invocation.InvocationParameters.class))).thenReturn(stream);
        AtomicReference<Consumer<ToolExecution>> callback = new AtomicReference<>();
        doAnswer(invocation -> {
            callback.set(invocation.getArgument(0));
            return stream;
        }).when(stream).onToolExecuted(any());
        SessionRegistry sessions = new SessionRegistryConfiguration().sessionRegistry();
        Session session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .provider(Provider.builder().streaming(true).streamingChatModel(mock(StreamingChatModel.class)).build())
                .build();
        sessions.insertIfAbsent(session);
        List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        var factory = mock(LlmClientFactory.class);
        when(factory.createClientForLoop(session)).thenReturn(ServiceResult.success(client));
        var result = TestSessionChat.start(TestSessionChat.service(sessions, factory, TestHistory.disabled(), TestMetrics.disabled()), session.getId(), new ClientCommand.Chat(requestId, "Hello"), events::add);
        assertEquals(ServiceResult.success(), result);

        var request = ToolExecutionRequest.builder().id("failed-1").name("ping").arguments("{}").build();
        TestSessionChat.await(() -> callback.get() != null);
        callback.get().accept(ToolExecution.builder().request(request)
                .result(ToolExecutionResult.builder().isError(true).resultText("Private error details").build())
                .invocationContext(mock(InvocationContext.class)).build());

        assertEquals(2, events.size());
        events.forEach(event -> assertEquals(requestId, event.requestId()));
        var failed = assertInstanceOf(ChatStreamEvent.ToolFailed.class, events.getLast());
        assertEquals(1, failed.round());
        assertEquals("failed-1", failed.toolCallId());
        assertEquals("ping", failed.toolName());
        assertEquals(ErrorCode.INTERNAL_ERROR, failed.error().code());
        assertEquals("Tool execution failed", failed.error().message());
    }

    @Test
    void multiPartToolCompletionDoesNotBreakTheStreamOrDiscardNativeContents() {
        TokenStream stream = mock(TokenStream.class, RETURNS_SELF);
        LlmClient client = mock(LlmClient.class);
        when(client.chatWithStream(any(UUID.class), eq("Hello"), any(dev.langchain4j.invocation.InvocationParameters.class))).thenReturn(stream);
        AtomicReference<Consumer<ToolExecution>> executed = new AtomicReference<>();
        AtomicReference<Consumer<ChatResponse>> completed = new AtomicReference<>();
        doAnswer(call -> { executed.set(call.getArgument(0)); return stream; }).when(stream).onToolExecuted(any());
        doAnswer(call -> { completed.set(call.getArgument(0)); return stream; }).when(stream).onCompleteResponse(any());
        var sessions = new SessionRegistryConfiguration().sessionRegistry();
        var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .provider(Provider.builder().streaming(true).streamingChatModel(mock(StreamingChatModel.class)).build()).build();
        sessions.insertIfAbsent(session);
        var factory = mock(LlmClientFactory.class); when(factory.createClientForLoop(session)).thenReturn(ServiceResult.success(client));
        var events = new java.util.concurrent.CopyOnWriteArrayList<ChatStreamEvent>();
        TestSessionChat.start(TestSessionChat.service(sessions, factory, TestHistory.disabled(), TestMetrics.disabled()), session.getId(), new ClientCommand.Chat(requestId, "Hello"), events::add);
        var result = ToolExecutionResult.builder().resultContents(List.of(
                dev.langchain4j.data.message.TextContent.from("part one"), dev.langchain4j.data.message.TextContent.from("part two"))).build();
        var request = ToolExecutionRequest.builder().id("multi-result").name("multi").arguments("{}").build();
        TestSessionChat.await(() -> executed.get() != null && completed.get() != null);
        executed.get().accept(ToolExecution.builder().request(request).result(result).invocationContext(mock(InvocationContext.class)).build());
        var event = assertInstanceOf(ChatStreamEvent.ToolCompleted.class, events.getLast());
        assertEquals("part one\npart two", event.result()); assertEquals(2, result.resultContents().size());
        completed.get().accept(ChatResponse.builder().aiMessage(AiMessage.from("Finished")).build());
        assertInstanceOf(ChatStreamEvent.Completed.class, events.getLast());
        assertTrue(events.stream().noneMatch(ChatStreamEvent.Failed.class::isInstance));
    }

    @Test
    void reportsToolFailureBeforeASubsequentModelFailure() {
        AtomicInteger calls = new AtomicInteger();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                if (calls.incrementAndGet() > 1) {
                    handler.onError(new IllegalStateException("Private provider error"));
                    return;
                }
                var toolRequest = ToolExecutionRequest.builder().id("broken-1").name("broken").arguments("{}").build();
                handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(toolRequest))
                        .finishReason(FinishReason.TOOL_EXECUTION).build());
            }
        };
        List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        stream(model, events, List.of(new BrokenTool()));

        assertEquals(5, events.size());
        assertInstanceOf(ChatStreamEvent.ToolStarted.class, events.get(2));
        var failed = assertInstanceOf(ChatStreamEvent.ToolFailed.class, events.get(3));
        assertEquals(1, failed.round());
        assertEquals("broken-1", failed.toolCallId());
        assertEquals("broken", failed.toolName());
        assertEquals("Tool execution failed", failed.error().message());
        assertInstanceOf(ChatStreamEvent.Failed.class, events.getLast());
    }

    @Test
    void exposesTheHandshakeCauseWhileKeepingAlreadyReceivedFragments() {
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onPartialResponse("Already received");
                handler.onError(new java.util.concurrent.CompletionException(
                        new javax.net.ssl.SSLHandshakeException("Remote host terminated the handshake")));
            }
        };
        List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        stream(model, events);
        assertEquals("Already received", assertInstanceOf(ChatStreamEvent.AnswerDelta.class, events.get(1)).text());
        var failed = assertInstanceOf(ChatStreamEvent.Failed.class, events.getLast());
        assertEquals(requestId, failed.requestId());
        assertEquals("TLS_HANDSHAKE_FAILED", failed.error().reason());
        assertEquals("SSLHandshakeException: Remote host terminated the handshake", failed.error().detail());
    }

    @Test
    void forwardsOnlyOneFailureAndSuppressesLateEvents() {
        AtomicReference<StreamingChatResponseHandler> callback = new AtomicReference<>();
        StreamingChatModel model = new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                callback.set(handler);
            }
        };
        List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        stream(model, events, List.of(), false);
        TestSessionChat.await(() -> callback.get() != null);
        callback.get().onError(new IllegalStateException("Private provider error"));
        callback.get().onPartialResponse("late response");
        callback.get().onError(new IllegalStateException("Repeated failure"));

        assertEquals(2, events.size());
        var failed = assertInstanceOf(ChatStreamEvent.Failed.class, events.getLast());
        assertEquals(ErrorCode.INTERNAL_ERROR, failed.error().code());
        assertEquals("Session stream failed", failed.error().message());
    }

    @Test
    void preservesProviderFailureWithoutEmittingEvents() {
        SessionRegistry sessions = new SessionRegistryConfiguration().sessionRegistry();
        Session session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .provider(Provider.builder().streaming(true).build()).build();
        sessions.insertIfAbsent(session);
        List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        var service = TestSessionChat.service(sessions, TestMemory.factory(List.of()), TestHistory.disabled(), TestMetrics.disabled());

        assertEquals(ServiceResult.failure(ErrorCode.NOT_IMPLEMENTED, "Provider has no streaming chat model"),
                TestSessionChat.start(service, session.getId(), new ClientCommand.Chat(requestId, "Hello"), events::add));
        assertTrue(events.isEmpty());
    }

    @Test
    void missingSessionReturnsNotFoundWithoutEmittingEvents() {
        var service = TestSessionChat.service(
                new SessionRegistryConfiguration().sessionRegistry(),
                TestMemory.factory(List.of()),
                TestHistory.disabled(),
                TestMetrics.disabled()
        );
        List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();

        assertEquals(ServiceResult.failure(ErrorCode.NOT_FOUND, "Session not found"),
                TestSessionChat.start(service, UUID.randomUUID(), new ClientCommand.Chat(requestId, "Hello"), events::add));
        assertTrue(events.isEmpty());
    }

    private void stream(StreamingChatModel model, List<ChatStreamEvent> events) {
        stream(model, events, List.of());
    }

    private void stream(StreamingChatModel model, List<ChatStreamEvent> events, List<IdentifiedTool> tools) {
        stream(model, events, tools, true);
    }

    private void stream(StreamingChatModel model, List<ChatStreamEvent> events, List<IdentifiedTool> tools, boolean wait) {
        SessionRegistry sessions = new SessionRegistryConfiguration().sessionRegistry();
        Session session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .provider(Provider.builder().streaming(true).streamingChatModel(model).build()).build();
        sessions.insertIfAbsent(session);
        DefaultLoopSessionStreamService service = TestSessionChat.service(sessions, TestMemory.factory(tools), TestHistory.disabled(), TestMetrics.disabled());

        ServiceResult<Void> result = TestSessionChat.start(service, session.getId(),
                new ClientCommand.Chat(requestId, "Hello"), event -> {
                    assertEquals(requestId, event.requestId());
                    events.add(event);
                });
        assertInstanceOf(ServiceResult.Success.class, result);
        assertEquals(ServiceResult.success(), result);
        if (wait) TestSessionChat.awaitTerminal(events);
    }

    public static class PingTool implements IdentifiedTool {
        private final UUID id = UUID.randomUUID();

        @Override
        public UUID id() {
            return id;
        }

        @Tool("Returns pong")
        public String ping() {
            return "pong";
        }
    }

    public static class BrokenTool implements IdentifiedTool {
        private final UUID id = UUID.randomUUID();

        @Override
        public UUID id() {
            return id;
        }

        @Tool("Fails during execution")
        public String broken() {
            throw new IllegalStateException("Private tool error");
        }
    }
}
