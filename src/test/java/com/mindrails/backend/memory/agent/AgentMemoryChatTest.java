package com.mindrails.backend.memory.agent;

import com.mindrails.backend.agent.service.internal.TestSessionChat;

import com.mindrails.backend.metrics.TestMetrics;

import com.mindrails.backend.history.TestHistory;

import static com.mindrails.backend.memory.agent.AgentMemoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class AgentMemoryChatTest {
    @Test
    void existingSessionsReadUpdatedAgentFactsWithoutSharingConversationWindows() {
        var f = new Fixture();
        UUID owner = f.agent(), first = f.session(owner), second = f.session(owner);
        UUID other = f.agent(), foreign = f.session(other);
        var entry = success(f.memory.create(owner, input("database", "Shared database is MySQL", true, null)));
        var firstSession = f.sessionRegistry.findManaged(first).orElseThrow();
        success(TestSessionChat.chat(f.streams, first, "First private request"));
        var firstStore = f.chatStore;
        success(TestSessionChat.chat(f.streams, second, "Second private request"));
        assertTrue(lastUser(f.model.requests.get(0)).contains(entry.content()));
        assertTrue(lastUser(f.model.requests.get(1)).contains(entry.content()));
        assertFalse(f.model.requests.get(1).messages().toString().contains("First private request"));
        var system = (SystemMessage) f.model.requests.getFirst().messages().getFirst();
        assertEquals("Literal {{instruction}}", system.text());
        assertTrue(f.model.requests.getFirst().toolSpecifications().stream()
                .anyMatch(tool -> tool.description().contains("Agent long-term memory tools:")));
        success(f.memory.update(owner, entry.id(), input("database", "Updated shared fact", true, 0L)));
        success(TestSessionChat.chat(f.streams, first, "Next request"));
        assertTrue(lastUser(f.model.requests.getLast()).contains("Updated shared fact"));
        assertFalse(lastUser(f.model.requests.getLast()).contains(entry.content()));
        var savedUsers = firstStore.getMessages(firstSession.getMemoryId()).stream()
                .filter(UserMessage.class::isInstance).map(UserMessage.class::cast).map(UserMessage::singleText).toList();
        assertEquals(List.of("First private request", "Next request"), savedUsers);
        success(TestSessionChat.chat(f.streams, foreign, "Foreign request"));
        assertTrue(lastUser(f.model.requests.getLast()).startsWith("Foreign request"));
        assertFalse(lastUser(f.model.requests.getLast()).contains(AgentMemoryRetrieval.PREFIX));
        success(f.memory.delete(owner, entry.id(), 1));
        success(TestSessionChat.chat(f.streams, first, "After deletion"));
        assertTrue(lastUser(f.model.requests.getLast()).startsWith("After deletion"));
        assertFalse(lastUser(f.model.requests.getLast()).contains(AgentMemoryRetrieval.PREFIX));
        UUID fork = success(f.agents.forkSession(owner, first));
        success(f.memory.create(owner, input("fork-fact", "Fork shares Agent facts", true, null)));
        success(TestSessionChat.chat(f.streams, fork, "Fork request"));
        assertTrue(lastUser(f.model.requests.getLast()).contains("Fork shares Agent facts"));
        assertTrue(f.model.requests.getLast().messages().toString().contains("First private request"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void toolFollowupKeepsInvocationFactsButDoesNotPersistInjectedText(boolean streaming) throws Exception {
        var f = new Fixture(new InMemoryAgentMemoryStore(), List.of(new PingTool()));
        var requests = new CopyOnWriteArrayList<ChatRequest>();
        var toolRequest = ToolExecutionRequest.builder().id("ping-1").name("ping").arguments("{}").build();
        var fact = new java.util.concurrent.atomic.AtomicReference<AgentMemoryEntry>();
        java.util.function.Function<ChatRequest, ChatResponse> respond = request -> {
            requests.add(request);
            if (requests.size() == 1) {
                var entry = fact.get();
                success(f.memory.update(entry.agentId(), entry.id(), input(entry.key(), "Next request decision", true, 0L)));
            }
            return requests.size() == 1 ? ChatResponse.builder().aiMessage(AiMessage.from(toolRequest)).build() : reply();
        };
        f.provider.setStreaming(streaming);
        f.provider.setChatModel(new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) { return respond.apply(request); }
        });
        f.provider.setStreamingChatModel(new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                handler.onCompleteResponse(respond.apply(request));
            }
        });
        UUID owner = f.agent(), session = f.session(owner);
        var entry = success(f.memory.create(owner, input("tool-fact", "Use the shared decision", true, null)));
        fact.set(entry);
        if (streaming) {
            var streams = TestSessionChat.service(f.sessionRegistry, f.factory, TestHistory.disabled(), TestMetrics.disabled());
            var events = new CopyOnWriteArrayList<ChatStreamEvent>();
            var finished = new CountDownLatch(1);
            success(TestSessionChat.start(streams, session, new ClientCommand.Chat(UUID.randomUUID(), "Call ping"), event -> {
                events.add(event);
                if (event instanceof ChatStreamEvent.Completed || event instanceof ChatStreamEvent.Failed) finished.countDown();
            }));
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertInstanceOf(ChatStreamEvent.Completed.class, events.getLast());
            var retrieved = events.stream().filter(ChatStreamEvent.Retrieved.class::isInstance)
                    .map(ChatStreamEvent.Retrieved.class::cast).findFirst().orElseThrow();
            assertEquals(entry.id().toString(), retrieved.sources().getFirst().sourceId());
            assertEquals("tool-fact", retrieved.sources().getFirst().title());
        } else success(TestSessionChat.chat(f.streams, session, "Call ping"));
        assertEquals(2, requests.size());
        requests.forEach(request -> {
            assertTrue(lastUser(request).contains(entry.content()));
            assertFalse(lastUser(request).contains("Next request decision"));
        });
        var memoryId = f.sessionRegistry.findManaged(session).orElseThrow().getMemoryId();
        assertEquals(List.of("Call ping"), f.chatStore.getMessages(memoryId).stream().filter(UserMessage.class::isInstance)
                .map(UserMessage.class::cast).map(UserMessage::singleText).toList());
    }

    @Test
    void cancellationDuringRetrievalStopsModelAndLatePreparationCannotDamageNextRun() throws Exception {
        var store = new BlockingStore();
        var f = new Fixture(store);
        var requests = new CopyOnWriteArrayList<ChatRequest>();
        f.provider.setStreaming(true);
        f.provider.setStreamingChatModel(new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
                handler.onCompleteResponse(reply());
            }
        });
        UUID owner = f.agent(), session = f.session(owner);
        success(f.memory.create(owner, input("fact", "Shared fact", true, null)));
        store.block = true;
        try (var executor = Executors.newSingleThreadExecutor()) {
            var streams = TestSessionChat.service(f.sessionRegistry, f.factory, TestHistory.disabled(), TestMetrics.disabled());
            var events = new CopyOnWriteArrayList<ChatStreamEvent>();
            success(TestSessionChat.start(streams, session, new ClientCommand.Chat(UUID.randomUUID(), "Cancelled request"), events::add));
            assertTrue(store.entered.await(3, TimeUnit.SECONDS));
            assertInstanceOf(ChatStreamEvent.Started.class, events.getFirst());
            success(streams.stop(session));
            store.block = false;
            store.release.countDown();
            executor.submit(() -> {}).get(3, TimeUnit.SECONDS);
            assertTrue(requests.isEmpty());
            assertEquals(2, events.size());
            assertInstanceOf(ChatStreamEvent.Cancelled.class, events.getLast());
            var memoryId = f.sessionRegistry.findManaged(session).orElseThrow().getMemoryId();
            assertTrue(f.chatStore.getMessages(memoryId).isEmpty());
            var finished = new CountDownLatch(1);
            success(TestSessionChat.start(streams, session, new ClientCommand.Chat(UUID.randomUUID(), "New request"), event -> {
                if (event instanceof ChatStreamEvent.Completed || event instanceof ChatStreamEvent.Failed) finished.countDown();
            }));
            assertTrue(finished.await(3, TimeUnit.SECONDS));
            assertEquals(1, requests.size());
            assertTrue(lastUser(requests.getFirst()).startsWith("New request"));
            assertEquals("New request", f.chatStore.getMessages(memoryId).stream().filter(UserMessage.class::isInstance)
                    .map(UserMessage.class::cast).toList().getLast().singleText());
        } finally { store.release.countDown(); }
    }

    @Test
    void retrievalFailureProducesFailedEventAndReleasesSessionWithoutCallingModel() {
        var store = new InMemoryAgentMemoryStore() {
            @Override public synchronized List<AgentMemoryEntry> entries(UUID memoryId) { throw new IllegalStateException("Injected storage failure"); }
        };
        var f = new Fixture(store);
        f.provider.setStreaming(true);
        f.provider.setStreamingChatModel(new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { fail("Model must not run"); }
        });
        UUID owner = f.agent(), session = f.session(owner);
        var streams = TestSessionChat.service(f.sessionRegistry, f.factory, TestHistory.disabled(), TestMetrics.disabled());
        var events = new CopyOnWriteArrayList<ChatStreamEvent>();
        success(TestSessionChat.start(streams, session, new ClientCommand.Chat(UUID.randomUUID(), "Request"), events::add));
        TestSessionChat.awaitTerminal(events);
        assertInstanceOf(ChatStreamEvent.Failed.class, events.getLast());
        var memoryId = f.sessionRegistry.findManaged(session).orElseThrow().getMemoryId();
        assertTrue(f.chatStore.getMessages(memoryId).isEmpty());
    }

    public static class PingTool implements IdentifiedTool {
        @Override public UUID id() { return UUID.fromString("00000000-0000-0000-0000-000000000099"); }
        @Tool("Returns pong") public String ping() { return "pong"; }
    }

    @Test
    void synchronousStorageFailureReturnsTypedErrorAndLeavesOriginalWindowUntouched() {
        var store = new InMemoryAgentMemoryStore() {
            @Override public synchronized List<AgentMemoryEntry> entries(UUID memoryId) { throw new IllegalStateException("Injected storage failure"); }
        };
        var f = new Fixture(store);
        UUID owner = f.agent(), session = f.session(owner);
        assertEquals(com.mindrails.backend.common.ServiceResult.ErrorCode.INTERNAL_ERROR,
                failure(TestSessionChat.chat(f.streams, session, "Request")));
        assertTrue(f.model.requests.isEmpty());
        var memoryId = f.sessionRegistry.findManaged(session).orElseThrow().getMemoryId();
        assertTrue(f.chatStore.getMessages(memoryId).isEmpty());
    }

    private static class BlockingStore extends InMemoryAgentMemoryStore {
        volatile boolean block;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        @Override public List<AgentMemoryEntry> entries(UUID memoryId) {
            if (block) {
                entered.countDown();
                try { if (!release.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("Test retrieval timeout"); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException(interrupted); }
            }
            return super.entries(memoryId);
        }
    }
}
