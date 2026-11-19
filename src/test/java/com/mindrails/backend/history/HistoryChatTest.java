package com.mindrails.backend.history;

import com.mindrails.backend.agent.service.internal.TestSessionChat;

import com.mindrails.backend.history.service.internal.HistoryRecords;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.store.HistoryStore;
import com.mindrails.backend.memory.ChatMemoryProperties;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
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
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class HistoryChatTest {
    static final JsonMapper JSON = JsonMapper.builder().build();

    static final class Fixture {
        final SessionRegistry sessions = new SessionRegistryConfiguration().sessionRegistry();
        final AgentRegistry agents = new AgentRegistryConfiguration().agentRegistry();

        final InMemoryChatMemoryStore memory = new InMemoryChatMemoryStore();
        final List<HistoryRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();
        final Session session;
        final LlmClientFactory factory;
        final ChatHistory recorder;
        final DefaultSessionService sync;
        final DefaultLoopSessionStreamService stream;
        final Agent agent =
                Agent.builder()
                        .id(UUID.randomUUID())
                        .ownerId(UUID.randomUUID())
                        .memoryId(UUID.randomUUID())
                        .name("Archive owner")
                        .build();

        Fixture(
                ChatModel model,
                StreamingChatModel streaming,
                HistoryStore override,
                List<IdentifiedTool> tools,
                int window) {
            Provider provider =
                    Provider.builder()
                            .id(UUID.randomUUID())
                            .streaming(streaming != null)
                            .chatModel(model)
                            .streamingChatModel(streaming)
                            .build();
            session =
                    Session.builder().ownerId(agent.getOwnerId())
                            .id(UUID.randomUUID())
                            .memoryId(UUID.randomUUID())
                            .parentAgentId(agent.getId())
                            .provider(provider)
                            .systemMessage("Literal instruction")
                            .build();
            agent.getSessions().add(session);
            agents.insertIfAbsent(agent);
            sessions.insertIfAbsent(session);
            var properties = new ChatMemoryProperties();
            properties.setMaxMessages(window);
            recorder =
                    TestHistory.create(override == null ? TestHistory.collect(records) : TestHistory.completed(override), agents);
            factory = TestLlmClients.create(tools, () -> memory, properties, null, null, null, null, recorder, TestMetrics.disabled());
            sync =
                    new DefaultSessionService(
                            sessions);
            stream =
                    TestSessionChat.service(
                            sessions,
                            factory,
                            recorder,
                            TestMetrics.disabled()
                    );
        }

        Fixture(ChatModel model, StreamingChatModel streaming) {
            this(model, streaming, null, List.of(), 100);
        }

        List<ChatStreamEvent> start(UUID request, String text) {
            List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
            assertInstanceOf(
                    ServiceResult.Success.class,
                    TestSessionChat.start(stream,
                            session.getId(), new ClientCommand.Chat(request, text), events::add));
            return events;
        }
    }

    @Test
    void synchronousRequestsCarryTheSameTurnIdIntoHistoryAndTools() {
        var f = new Fixture(new ChatModel() {
            @Override public ChatResponse chat(ChatRequest request) { return reply("Done"); }
        }, null);
        UUID request = UUID.randomUUID();
        assertInstanceOf(ServiceResult.Success.class, TestSessionChat.chat(f.stream, f.session.getId(), "Draw", request));
        assertFalse(f.records.isEmpty());
        assertTrue(f.records.stream().allMatch(record -> request.equals(record.requestId())));
        f.records.clear();
        assertInstanceOf(ServiceResult.Success.class, TestSessionChat.chat(f.stream, f.session.getId(), "Legacy client"));
        assertNotNull(f.records.getFirst().requestId());
        assertNotEquals(request, f.records.getFirst().requestId());
    }

    static ChatResponse reply(String answer) {
        return ChatResponse.builder()
                .aiMessage(AiMessage.from(answer))
                .finishReason(FinishReason.STOP)
                .tokenUsage(new TokenUsage(2, 3, 5))
                .build();
    }

    static ChatResponse tool(int round) {
        return ChatResponse.builder()
                .aiMessage(
                        AiMessage.from(
                                ToolExecutionRequest.builder()
                                        .id("tool-" + round)
                                        .name("ping")
                                        .arguments("{}")
                                        .build()))
                .finishReason(FinishReason.TOOL_EXECUTION)
                .build();
    }

    public static final class Ping implements IdentifiedTool {
        @Override
        public UUID id() {
            return UUID.randomUUID();
        }

        @Tool
        public String ping() {
            return "pong";
        }
    }

    @Test
    void reusedClientReadsEachTurnsHistoryFromInvocationParameters() {
        var f = new Fixture(new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                return reply("[\"Done\"]");
            }
        }, null);
        assertInstanceOf(ServiceResult.Success.class, f.factory.createClientForLoop(f.session));
        var client = ((ServiceResult.Success<com.mindrails.backend.agent.model.LlmClient>) f.factory.createClientForLoop(f.session)).value();
        var recorder = f.recorder;

        for (String message : List.of("First turn", "Second turn")) {
            var parameters = LlmClient.parameters(f.session, null, "REST");
            recorder.record(HistoryRecords.start(parameters, message));
            TestLlmClients.chat(client, f.session.getMemoryId(), message, parameters);
            // A direct call without a turn must not append to the preceding turn's archive.
            TestLlmClients.chat(client, f.session.getMemoryId(), "Untracked call");
            recorder.record(HistoryRecords.finish(parameters, HistoryRecord.Status.COMPLETED, null, null, null, null, null));
        }

        assertEquals(2, f.records.size());
        for (int i = 0; i < f.records.size(); i++) {
            var record = f.records.get(i);
            var messages = ChatMessageDeserializer.messagesFromJson(record.messages());
            assertEquals(2, messages.size());
            assertEquals(UserMessage.from(i == 0 ? "First turn" : "Second turn"), messages.getFirst());
            assertNotNull(record.invocationId());
        }
        assertNotEquals(f.records.getFirst().invocationId(), f.records.getLast().invocationId());
        assertNotSame(client, ((ServiceResult.Success<com.mindrails.backend.agent.model.LlmClient>) f.factory.createClientForLoop(f.session)).value());
    }

    @Test
    void overlappingInvocationsKeepTheirHistorySeparate() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var f = new Fixture(new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                if (request.messages().getLast().toString().contains("First turn")) {
                    entered.countDown();
                    try {
                        assertTrue(release.await(5, TimeUnit.SECONDS));
                    } catch (InterruptedException failure) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(failure);
                    }
                    return reply("[\"First response\"]");
                }
                return reply("[\"Second response\"]");
            }
        }, null);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> TestSessionChat.chat(f.stream, f.session.getId(), "First turn"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var second = executor.submit(() -> TestSessionChat.chat(f.stream, f.session.getId(), "Second turn"));
                assertFalse(second.isDone());
                release.countDown();
                assertEquals("[\"First response\"]", ((ServiceResult.Success<ChatStreamEvent.Completed>) first.get(5, TimeUnit.SECONDS)).value().answer());
                assertEquals("[\"Second response\"]", ((ServiceResult.Success<ChatStreamEvent.Completed>) second.get(5, TimeUnit.SECONDS)).value().answer());
            } finally { release.countDown(); }
        }
        assertEquals(2, f.records.size());
        var secondMessages = ChatMessageDeserializer.messagesFromJson(f.records.getLast().messages());
        var firstMessages = ChatMessageDeserializer.messagesFromJson(f.records.getFirst().messages());
        assertEquals(List.of(UserMessage.from("Second turn"), AiMessage.from("[\"Second response\"]")), secondMessages);
        assertEquals(List.of(UserMessage.from("First turn"), AiMessage.from("[\"First response\"]")), firstMessages);
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void archivesEveryToolRoundOutsideTheWindowAndOnlyNewMessages(boolean streaming) {
        AtomicInteger calls = new AtomicInteger();
        ChatModel syncModel =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        int round = calls.incrementAndGet();
                        return round <= 3 ? tool(round) : reply("[\"Done\"]");
                    }
                };
        StreamingChatModel streamModel =
                new StreamingChatModel() {
                    @Override
                    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                        int round = calls.incrementAndGet();
                        handler.onPartialResponse(round <= 3 ? "Checking" : "Draft");
                        var handle = mock(StreamingHandle.class);
                        handler.onPartialThinking(
                                new PartialThinking("Reason"), new PartialThinkingContext(handle));
                        handler.onCompleteResponse(round <= 3 ? tool(round) : reply("Done"));
                    }
                };
        var f =
                new Fixture(
                        streaming ? null : syncModel,
                        streaming ? streamModel : null,
                        null,
                        List.of(new Ping()),
                        3);
        var events = f.start(UUID.randomUUID(), "Original input");
        TestSessionChat.awaitTerminal(events);
        assertInstanceOf(ChatStreamEvent.Completed.class, events.getLast());
        assertEquals(List.of(1, 2, 3), events.stream().filter(ChatStreamEvent.ToolCompleted.class::isInstance)
                .map(ChatStreamEvent.ToolCompleted.class::cast).map(ChatStreamEvent.ToolCompleted::round).toList());
        assertEquals(1, f.records.size());
        var record = f.records.getFirst();
        var messages = ChatMessageDeserializer.messagesFromJson(record.messages());
        assertEquals(8, messages.size());
        assertEquals(UserMessage.from("Original input"), messages.getFirst());
        assertEquals(
                3, messages.stream().filter(ToolExecutionResultMessage.class::isInstance).count());
        if (streaming) {
            assertEquals("Checking", ((AiMessage) messages.get(1)).text());
            assertEquals("Reason", ((AiMessage) messages.get(1)).thinking());
            assertEquals("Done", ((AiMessage) messages.getLast()).text());
            assertFalse(record.messages().contains("Draft"));
        }
        assertEquals(f.agent.getOwnerId(), record.ownerId());
        assertNotNull(record.invocationId());
        assertEquals(HistoryRecord.Status.COMPLETED, record.status());
        assertFalse(JSON.readTree(record.metadata()).has("partial"));
        assertTrue(f.memory.getMessages(f.session.getMemoryId()).size() <= 3);
        if (streaming) {
            TestSessionChat.chat(f.stream, f.session.getId(), "Next input");
        } else {
            TestSessionChat.chat(f.stream, f.session.getId(), "Next input");
        }
        assertEquals(2, f.records.size());
        var next = ChatMessageDeserializer.messagesFromJson(f.records.getLast().messages());
        assertEquals(2, next.size());
        assertEquals(UserMessage.from("Next input"), next.getFirst());
        assertNotEquals(record.id(), f.records.getLast().id());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void writeFailurePreservesSuccessWithoutRerunningModel(boolean streaming) {
        AtomicInteger models = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        ChatModel model =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        models.incrementAndGet();
                        return reply("[\"Done\"]");
                    }
                };
        StreamingChatModel streamModel =
                new StreamingChatModel() {
                    @Override
                    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                        models.incrementAndGet();
                        handler.onPartialResponse("Done");
                        handler.onCompleteResponse(reply("Done"));
                    }
                };
        HistoryStore unavailable =
                record -> {
                    writes.incrementAndGet();
                    throw new IllegalStateException("secret JDBC detail");
                };
        var f =
                new Fixture(
                        streaming ? null : model,
                        streaming ? streamModel : null,
                        unavailable,
                        List.of(),
                        100);
        if (streaming) {
            var events = f.start(UUID.randomUUID(), "Hello");
            TestSessionChat.awaitTerminal(events);
            assertInstanceOf(ChatStreamEvent.Completed.class, events.getLast());
            assertFalse(events.stream().anyMatch(ChatStreamEvent.Failed.class::isInstance));
        } else {
            assertEquals("[\"Done\"]", ((ServiceResult.Success<ChatStreamEvent.Completed>) TestSessionChat.chat(f.stream, f.session.getId(), "Hello")).value().answer());
        }
        assertEquals(1, models.get());
        assertEquals(1, writes.get());
    }

    @Test
    void failureBeforeAnAiResponseArchivesInputWithSafeError() {
        var f =
                new Fixture(
                        new ChatModel() {
                            @Override
                            public ChatResponse doChat(ChatRequest request) {
                                throw new IllegalArgumentException("private provider details");
                            }
                        },
                        null);
        assertInstanceOf(ServiceResult.Failure.class, TestSessionChat.chat(f.stream, f.session.getId(), "Hello"));
        assertEquals(HistoryRecord.Status.FAILED, f.records.getFirst().status());
        assertEquals(
                List.of(UserMessage.from("Hello")),
                ChatMessageDeserializer.messagesFromJson(f.records.getFirst().messages()));
        assertFalse(f.records.getFirst().metadata().contains("private"));
    }

    public static final class EchoPayload implements IdentifiedTool {
        public UUID id() { return UUID.fromString("3a737946-9dcf-4de1-9382-f9246c4d3f51"); }
        @Tool public String echo_payload(@dev.langchain4j.agent.tool.P("content") String content) { return content; }
    }

    @Test
    void compactSynchronousActivityDoesNotChangeModelInputOrFullArchive() {
        String body = "complete tool payload ".repeat(1000);
        var calls = new AtomicInteger();
        var model = new ChatModel() {
            public ChatResponse doChat(ChatRequest request) {
                if (calls.incrementAndGet() == 1) return ChatResponse.builder().aiMessage(AiMessage.from(
                        ToolExecutionRequest.builder().id("echo").name("echo_payload")
                                .arguments(JSON.writeValueAsString(java.util.Map.of("content", body))).build()))
                        .finishReason(FinishReason.TOOL_EXECUTION).build();
                var result = (ToolExecutionResultMessage) request.messages().getLast();
                assertEquals(body, result.text());
                return reply("[\"Done\"]");
            }
        };
        var f = new Fixture(model, null, null, List.of(new EchoPayload()), 10);
        var events = f.start(UUID.randomUUID(), "Echo");
        TestSessionChat.awaitTerminal(events);
        var activity = events.stream().filter(ChatStreamEvent.ToolCompleted.class::isInstance)
                .map(ChatStreamEvent.ToolCompleted.class::cast).findFirst().orElseThrow();
        assertEquals(body, activity.result());
        assertTrue(f.records.getFirst().messages().contains(body));
        var display = com.mindrails.backend.tool.display.ToolUiProjection.history(f.records.getFirst());
        assertFalse(display.messages().contains(body));
        assertTrue(display.messages().length() < f.records.getFirst().messages().length() / 10);
    }

    @Test
    void failedStreamRetainsPartialAnswerAndThinkingWithoutWritingArgumentFragments() {
        var pending = new AtomicReference<StreamingChatResponseHandler>();
        var f =
                new Fixture(
                        null,
                        new StreamingChatModel() {
                            @Override
                            public void doChat(
                                    ChatRequest request, StreamingChatResponseHandler handler) {
                                pending.set(handler);
                            }
                        });
        var events = f.start(UUID.randomUUID(), "Hello");
        TestSessionChat.await(() -> pending.get() != null);
        var handler = pending.get();
        var handle = mock(StreamingHandle.class);
        handler.onPartialResponse(new PartialResponse("Half "), new PartialResponseContext(handle));
        handler.onPartialResponse(
                new PartialResponse("answer"), new PartialResponseContext(handle));
        handler.onPartialThinking(
                new PartialThinking("Thinking"), new PartialThinkingContext(handle));
        handler.onPartialToolCall(
                PartialToolCall.builder()
                        .index(0)
                        .id("pending")
                        .name("ping")
                        .partialArguments("{")
                        .build());
        handler.onError(new IllegalStateException("secret"));
        assertEquals(HistoryRecord.Status.FAILED, f.records.getFirst().status());
        var partial = JSON.readTree(f.records.getFirst().metadata()).get("partial");
        assertEquals("Half answer", partial.get("answer").asText());
        assertEquals("Thinking", partial.get("thinking").asText());
        assertFalse(partial.has("toolCalls"));
        assertInstanceOf(ChatStreamEvent.Failed.class, events.getLast());
    }

    @Test
    void cancelArchivesOnceAndLateCallbacksCannotPolluteTheNextRunEvenWithReusedRequestId() {
        List<StreamingChatResponseHandler> handlers = new java.util.concurrent.CopyOnWriteArrayList<>();
        var f =
                new Fixture(
                        null,
                        new StreamingChatModel() {
                            @Override
                            public void doChat(
                                    ChatRequest request, StreamingChatResponseHandler handler) {
                                handlers.add(handler);
                            }
                        });
        UUID reused = UUID.randomUUID();
        var firstEvents = f.start(reused, "First");
        var handle = mock(StreamingHandle.class);
        TestSessionChat.await(() -> handlers.size() == 1);
        handlers.getFirst()
                .onPartialResponse(
                        new PartialResponse("Partial"), new PartialResponseContext(handle));
        assertEquals(
                ServiceResult.success(),
                f.stream.stop(f.session.getId()));
        assertEquals(HistoryRecord.Status.CANCELLED, f.records.getFirst().status());
        verify(handle).cancel();
        var secondEvents = f.start(reused, "Second");
        TestSessionChat.await(() -> handlers.size() == 2);
        handlers.getFirst().onError(new IllegalStateException("late"));
        handlers.getFirst().onCompleteResponse(reply("Late answer"));
        handlers.getLast().onCompleteResponse(reply("New answer"));
        handlers.getLast().onCompleteResponse(reply("Duplicate"));
        assertEquals(2, f.records.size());
        assertNotEquals(f.records.getFirst().id(), f.records.getLast().id());
        assertEquals(reused, f.records.getFirst().requestId());
        assertEquals(reused, f.records.getLast().requestId());
        assertFalse(f.records.getLast().messages().contains("Late answer"));
        assertFalse(f.records.getLast().messages().contains("Duplicate"));
        assertEquals(3, firstEvents.size());
        assertInstanceOf(ChatStreamEvent.Completed.class, secondEvents.getLast());
    }

    @Test
    void configurationUpdateLeavesTheRunningTurnsArchiveAttached() {
        var pending = new AtomicReference<StreamingChatResponseHandler>();
        var fixture = new Fixture(null, new StreamingChatModel() {
            @Override
            public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                pending.set(handler);
            }
        });
        var events = fixture.start(UUID.randomUUID(), "In flight");
        TestSessionChat.await(() -> pending.get() != null);

        fixture.sessions.save(fixture.session.toBuilder().systemMessage("Next turn instructions").build());
        pending.get().onCompleteResponse(reply("Completed after configuration update"));
        TestSessionChat.awaitTerminal(events);
        assertInstanceOf(ChatStreamEvent.Completed.class, events.getLast());
        assertEquals(HistoryRecord.Status.COMPLETED, fixture.records.getFirst().status());
        assertTrue(fixture.records.getFirst().messages().contains("Completed after configuration update"));
    }

    @Test
    void queuedMessagesOnlyBecomeHistoryTurnsWhenExecutionStarts() {
        var pending = new AtomicReference<StreamingChatResponseHandler>();
        var f = new Fixture(null, new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { pending.set(handler); }
        });
        assertInstanceOf(ServiceResult.Failure.class, TestSessionChat.chat(f.stream, UUID.randomUUID(), "Missing"));
        assertTrue(f.records.isEmpty());
        var events = f.start(UUID.randomUUID(), "Accepted");
        TestSessionChat.await(() -> pending.get() != null);
        assertInstanceOf(ServiceResult.Success.class, TestSessionChat.start(f.stream, f.session.getId(),
                new ClientCommand.Chat(UUID.randomUUID(), "Queued"), ignored -> {}));
        assertTrue(f.records.isEmpty());
        f.stream.stop(f.session.getId());
        assertEquals(1, f.records.size());
        assertEquals(HistoryRecord.Status.CANCELLED, f.records.getFirst().status());
        assertInstanceOf(ChatStreamEvent.Cancelled.class, events.getLast());
    }

    @Test
    void archiveRemainsAfterDeletingAgentAndSession() {
        var f =
                new Fixture(
                        new ChatModel() {
                            @Override
                            public ChatResponse doChat(ChatRequest request) {
                                return reply("[\"Done\"]");
                            }
                        },
                        null);
        TestSessionChat.chat(f.stream, f.session.getId(), "Preserve archive");
        var service = new DefaultAgentService(f.agents, f.sync, List.of(f.session.getProvider()));
        assertEquals(ServiceResult.success(), service.delete(f.agent.getId()));
        assertTrue(f.agents.findManaged(f.agent.getId()).isEmpty());
        assertTrue(f.sessions.findManaged(f.session.getId()).isEmpty());
        assertEquals(f.session.getId(), f.records.getFirst().sessionId());
        assertEquals(f.agent.getId(), f.records.getFirst().agentId());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void rawUserInputExcludesInjectedMemoryAndRepairedToolFailureIsPreserved(boolean streaming) {
        AtomicInteger calls = new AtomicInteger();
        var requests = new ArrayList<ChatRequest>();
        var badTool =
                ChatResponse.builder()
                        .aiMessage(
                                AiMessage.from(
                                        ToolExecutionRequest.builder()
                                                .id("bad-create")
                                                .name("agent_memory_create")
                                                .arguments(
                                                        "{\"key\":\"bad"
                                                                + " key\",\"content\":\"Fact\"}")
                                                .build()))
                        .build();
        ChatModel model =
                new ChatModel() {
                    @Override
                    public ChatResponse doChat(ChatRequest request) {
                        requests.add(request);
                        return calls.incrementAndGet() == 1 ? badTool : reply("[\"Done\"]");
                    }
                };
        StreamingChatModel streamModel =
                new StreamingChatModel() {
                    @Override
                    public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                        requests.add(request);
                        handler.onCompleteResponse(
                                calls.incrementAndGet() == 1 ? badTool : reply("Done"));
                    }
                };
        var f = new Fixture(streaming ? null : model, streaming ? streamModel : null);
        var properties = new com.mindrails.backend.memory.agent.AgentMemoryProperties();

        var agentMemory =
                new com.mindrails.backend.memory.agent.AgentMemoryService(
                        f.agents,
                        f.sessions,
                        new com.mindrails.backend.memory.agent.InMemoryAgentMemoryStore(),
                        properties
                );
        assertInstanceOf(
                ServiceResult.Success.class,
                agentMemory.create(
                        f.agent.getId(),
                        new com.mindrails.backend.memory.agent.AgentMemoryInput(
                                "preference",
                                com.mindrails.backend.memory.agent.AgentMemoryEntry.Type.PREFERENCE,
                                "Injected preference",
                                List.of(),
                                false,
                                0,
                                null,
                                null)));
        var factory =
                TestLlmClients.create(List.of(), () -> f.memory, new ChatMemoryProperties(), agentMemory, null, null, null, f.recorder, TestMetrics.disabled());
        var recorder = f.recorder;
        var streams = TestSessionChat.service(f.sessions, factory, recorder, TestMetrics.disabled());
        assertInstanceOf(ServiceResult.Success.class, TestSessionChat.chat(streams, f.session.getId(), "Hello"));
        assertTrue(
                requests.getFirst().messages().stream()
                        .filter(UserMessage.class::isInstance)
                        .map(UserMessage.class::cast)
                        .anyMatch(message -> message.singleText().contains("Injected preference")));
        var record = f.records.getFirst();
        var messages = ChatMessageDeserializer.messagesFromJson(record.messages());
        assertEquals(UserMessage.from("Hello"), messages.getFirst());
        assertFalse(record.messages().contains("Injected preference"));
        assertEquals(HistoryRecord.Status.COMPLETED, record.status());
        var result =
                messages.stream()
                        .filter(ToolExecutionResultMessage.class::isInstance)
                        .map(ToolExecutionResultMessage.class::cast)
                        .findFirst()
                        .orElseThrow();
        assertEquals("bad-create", result.id());
        assertEquals(true, result.isError());
        assertEquals(4, messages.size());
    }

    @Test
    void cancellationWriteFailureDoesNotChangeTheCancelResult() {
        var f =
                new Fixture(
                        null,
                        new StreamingChatModel() {
                            @Override
                            public void doChat(
                                    ChatRequest request, StreamingChatResponseHandler handler) {}
                        },
                        record -> {
                            assertEquals(HistoryRecord.Status.CANCELLED, record.status());
                            throw new IllegalStateException("JDBC private");
                        },
                        List.of(),
                        100);
        f.start(UUID.randomUUID(), "Hello");
        assertInstanceOf(ServiceResult.Success.class,
                f.stream.stop(f.session.getId()));
    }

    @Test
    void streamSuccessIsDeliveredOnlyAfterTheHistoryInsert() {
        var stored = new java.util.concurrent.atomic.AtomicBoolean();
        var f =
                new Fixture(
                        null,
                        new StreamingChatModel() {
                            @Override
                            public void doChat(
                                    ChatRequest request, StreamingChatResponseHandler handler) {
                                handler.onCompleteResponse(reply("Done"));
                            }
                        },
                        record -> stored.set(true),
                        List.of(),
                        100);
        var events = new java.util.concurrent.CopyOnWriteArrayList<ChatStreamEvent>();
        TestSessionChat.start(f.stream,
                f.session.getId(),
                new ClientCommand.Chat(UUID.randomUUID(), "Hello"),
                event -> {
                    if (event instanceof ChatStreamEvent.Completed) {
                        assertTrue(stored.get());
                    }
                    events.add(event);
                });
        TestSessionChat.awaitTerminal(events);
        assertInstanceOf(ChatStreamEvent.Completed.class, events.getLast());
    }

    @Test
    void historyInsertFinishesBeforeSuccessDeliveryAndSessionRemainsBusyWhileWriting()
            throws Exception {
        CountDownLatch writing = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        var f =
                new Fixture(
                        new ChatModel() {
                            @Override
                            public ChatResponse doChat(ChatRequest request) {
                                return reply("[\"Done\"]");
                            }
                        },
                        null,
                        record -> {
                            writing.countDown();
                            try {
                                assertTrue(release.await(3, TimeUnit.SECONDS));
                            } catch (InterruptedException error) {
                                throw new RuntimeException(error);
                            }
                        },
                        List.of(),
                        100);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var result = executor.submit(() -> TestSessionChat.chat(f.stream, f.session.getId(), "Hello"));
            try {
                assertTrue(writing.await(3, TimeUnit.SECONDS));
                assertFalse(result.isDone());
                assertInstanceOf(ServiceResult.Success.class, f.sync.deleteSession(f.session.getId()));
                assertTrue(f.sessions.findManaged(f.session.getId()).isEmpty());
            } finally {
                release.countDown();
            }
            assertEquals("[\"Done\"]", ((ServiceResult.Success<ChatStreamEvent.Completed>) result.get(3, TimeUnit.SECONDS)).value().answer());
        }
    }
}
