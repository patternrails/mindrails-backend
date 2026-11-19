package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.api.websocket.protocol.*;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.service.SessionCommunicationService;
import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import com.mindrails.backend.agent.tool.SessionCommunicationTool;
import com.mindrails.backend.agent.tool.SessionListTool;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.memory.ChatMemoryProperties;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.provider.Provider;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import tools.jackson.databind.json.JsonMapper;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionCommunicationIntegrationTest {
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Test @SuppressWarnings("unchecked")
    void realToolsDeliverAndReplyWithoutATargetSinkAndKeepIndependentMemoryAndHistory() throws Exception {
        UUID owner = UUID.randomUUID(), a = UUID.randomUUID(), b = UUID.randomUUID();
        var registry = mock(SessionRegistry.class);
        ObjectProvider<LoopSessionStreamService> provider = mock(ObjectProvider.class);
        var communication = new SessionCommunicationTool(new SessionCommunicationService(registry, provider));
        var discovery = new SessionListTool(registry, mock(com.mindrails.backend.agent.registry.AgentRegistry.class));
        when(registry.discover(eq(owner), eq(a), eq("Rust"), eq(0), eq(21))).thenReturn(List.of(
                new SessionRegistry.Candidate(b, UUID.randomUUID(), "Rust ownership", java.time.Instant.now())));
        var memory = new InMemoryChatMemoryStore();
        var factory = TestLlmClients.create(List.of(communication, discovery), () -> memory, new ChatMemoryProperties());
        var archives = new CopyOnWriteArrayList<HistoryRecord>();
        var history = TestHistory.create(TestHistory.collect(archives), null);
        var loops = new DefaultLoopSessionStreamService(registry, factory, history, TestMetrics.disabled(), null);
        var statuses = new com.mindrails.backend.userstatus.UserSessionStatusService();
        org.springframework.test.util.ReflectionTestUtils.setField(loops, "userStatuses", statuses);
        var userEvents = new CopyOnWriteArrayList<com.mindrails.backend.userstatus.UserSessionStatusService.Event>();
        statuses.subscribe(owner, userEvents::add);
        when(provider.getObject()).thenReturn(loops);
        var initialRequest = new AtomicReference<ChatRequest>();
        var incomingId = new AtomicReference<UUID>();
        var completed = new LinkedBlockingQueue<ChatStreamEvent>();
        var events = new CopyOnWriteArrayList<ChatStreamEvent>();
        ChatModel modelA = model(request -> {
            if (request.messages().getLast() instanceof ToolExecutionResultMessage result) {
                if (SessionListTool.NAME.equals(result.toolName())) {
                    UUID discovered = UUID.fromString(JSON.readTree(result.text()).path("sessions").get(0).path("sessionId").asString());
                    return call("send", discovered, "Please reply with result from B", null);
                }
                incomingId.set(UUID.fromString(JSON.readTree(result.text()).path("messageId").asString()));
                return AiMessage.from("Delegated");
            }
            var text = ((UserMessage) request.messages().getLast()).singleText();
            return text.equals("delegate") ? AiMessage.from(List.of(ToolExecutionRequest.builder().id("list")
                    .name(SessionListTool.NAME).arguments("{\"query\":\"Rust\"}").build())) : AiMessage.from("A received the result");
        });
        ChatModel modelB = model(request -> {
            if (request.messages().getLast() instanceof ToolExecutionResultMessage) return AiMessage.from("B finished");
            initialRequest.set(request);
            String text = ((UserMessage) request.messages().getLast()).singleText();
            var match = java.util.regex.Pattern.compile("Message ID: ([a-f0-9-]{36})").matcher(text);
            assertTrue(match.find());
            return call("reply", a, "result from B", UUID.fromString(match.group(1)));
        });
        var source = session(a, owner, modelA);
        var target = session(b, owner, modelB);
        when(registry.findManaged(a)).thenReturn(Optional.of(source));
        when(registry.findManaged(b)).thenReturn(Optional.of(target));
        try {
            loops.bind(a, event -> {
                events.add(event);
                if (event instanceof ChatStreamEvent.Completed || event instanceof ChatStreamEvent.Failed) completed.add(event);
            });
            assertInstanceOf(ServiceResult.Success.class, loops.enqueue(a, new ClientCommand.Chat(UUID.randomUUID(), "delegate")));
            assertInstanceOf(ChatStreamEvent.Completed.class, completed.poll(5, TimeUnit.SECONDS));
            var reply = assertInstanceOf(ChatStreamEvent.Completed.class, completed.poll(5, TimeUnit.SECONDS));
            assertEquals("A received the result", reply.answer());
            var queuedEvent = events.stream().filter(ChatStreamEvent.Enqueued.class::isInstance)
                    .filter(event -> event.requestId().equals(reply.requestId())).findFirst().orElseThrow();
            var startedEvent = events.stream().filter(ChatStreamEvent.Started.class::isInstance)
                    .filter(event -> event.requestId().equals(reply.requestId())).findFirst().orElseThrow();
            assertTrue(events.indexOf(queuedEvent) < events.indexOf(startedEvent));
            assertNotNull(initialRequest.get());
            assertTrue(initialRequest.get().messages().stream().noneMatch(m -> m instanceof UserMessage u && u.singleText().equals("delegate")));
            assertNotEquals(source.getMemoryId(), target.getMemoryId());
            assertTrue(memory.getMessages(target.getMemoryId()).stream().anyMatch(m -> m instanceof UserMessage u && u.singleText().contains(a.toString())));
            assertTrue(memory.getMessages(source.getMemoryId()).stream().anyMatch(m -> m instanceof UserMessage u && u.singleText().contains("result from B")));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (archives.size() < 3 && System.nanoTime() < deadline) Thread.sleep(10);
            assertEquals(3, archives.size());
            var targetArchive = archives.stream().filter(r -> b.equals(r.sessionId())).findFirst().orElseThrow();
            assertEquals(incomingId.get(), targetArchive.requestId());
            assertEquals(a.toString(), JSON.readTree(targetArchive.metadata()).path("communication").path("sourceSessionId").asString());
            assertEquals("Please reply with result from B", JSON.readTree(targetArchive.metadata()).path("input").asString());
            assertTrue(userEvents.stream().anyMatch(event -> event.data() instanceof com.mindrails.backend.userstatus.UserSessionStatusService.Status s
                    && b.equals(s.sessionId()) && s.state().equals("running")));
            assertTrue(userEvents.stream().anyMatch(event -> event.data() instanceof com.mindrails.backend.userstatus.UserSessionStatusService.Status s
                    && b.equals(s.sessionId()) && s.lastFinished() != null && s.lastFinished().outcome().equals("completed")));
        } finally { loops.shutdown(); }
    }

    @Test void taskClientDoesNotExposeSessionCommunication() {
        var tool = new SessionCommunicationTool(mock(SessionCommunicationService.class));
        var factory = TestLlmClients.create(List.of(tool), InMemoryChatMemoryStore::new, new ChatMemoryProperties());
        var captured = new AtomicReference<ChatRequest>();
        var task = Task.builder().id(UUID.randomUUID()).ownerId(UUID.randomUUID()).parentAgentId(UUID.randomUUID())
                .provider(Provider.builder().id(UUID.randomUUID()).streaming(false).chatModel(model(request -> {
                    captured.set(request); return AiMessage.from("[\"done\"]");
                })).build()).build();
        UUID execution = UUID.randomUUID();
        var client = ((ServiceResult.Success<LlmClient>) factory.forTask(task, "Task instructions", execution, null)).value();
        client.chatWithLines(execution, "task", LlmClient.parameters(task, execution, UUID.randomUUID(), java.time.Instant.now(), "INTERNAL"));
        assertTrue(captured.get().parameters().toolSpecifications() == null
                || captured.get().parameters().toolSpecifications().stream().noneMatch(spec -> SessionCommunicationTool.NAME.equals(spec.name())));
    }

    @Test void lateBindingSnapshotsActiveAndQueuedInputsBeforeTheirSubsequentEvents() throws Exception {
        var client = mock(LlmClient.class);
        var tokens = mock(dev.langchain4j.service.TokenStream.class, RETURNS_SELF);
        var started = new CountDownLatch(1);
        doAnswer(call -> { started.countDown(); return null; }).when(tokens).start();
        when(client.chatWithStream(any(), anyString(), any())).thenReturn(tokens);
        try (var executor = Executors.newSingleThreadExecutor()) {
            var loop = new LlmRunLoop(client, executor, TestHistory.disabled(), TestMetrics.disabled());
            var session = session(UUID.randomUUID(), UUID.randomUUID(), model(request -> AiMessage.from("ok")));
            UUID active = UUID.randomUUID(), queued = UUID.randomUUID();
            loop.enqueue(new DefaultLoopSessionStreamService.MessageToLLM(active, session.getMemoryId(), "active", LlmClient.parameters(session, active, "WEBSOCKET")));
            assertTrue(started.await(5, TimeUnit.SECONDS));
            loop.enqueue(new DefaultLoopSessionStreamService.MessageToLLM(queued, session.getMemoryId(), "queued", LlmClient.parameters(session, queued, "WEBSOCKET")));
            var events = new ArrayList<ChatStreamEvent>();
            loop.registerEventSink(events::add);
            assertEquals(List.of(active, queued), events.stream().map(ChatStreamEvent::requestId).toList());
            assertEquals("active", assertInstanceOf(ChatStreamEvent.Started.class, events.getFirst()).input().message());
            assertEquals("queued", assertInstanceOf(ChatStreamEvent.Enqueued.class, events.getLast()).message());
            loop.stop();
        }
    }

    private static Session session(UUID id, UUID owner, ChatModel model) {
        return Session.builder().id(id).ownerId(owner).parentAgentId(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .provider(Provider.builder().id(UUID.randomUUID()).streaming(false).chatModel(model).build()).build();
    }
    private static ChatModel model(java.util.function.Function<ChatRequest, AiMessage> response) {
        return new ChatModel() { @Override public ChatResponse doChat(ChatRequest request) { return ChatResponse.builder().aiMessage(response.apply(request)).build(); } };
    }
    private static AiMessage call(String id, UUID target, String text, UUID reply) {
        var arguments = new LinkedHashMap<String, Object>();
        arguments.put("targetSessionId", target); arguments.put("message", text);
        if (reply != null) arguments.put("inReplyTo", reply);
        return AiMessage.from(List.of(ToolExecutionRequest.builder().id(id).name(SessionCommunicationTool.NAME)
                .arguments(JSON.writeValueAsString(arguments)).build()));
    }
}
