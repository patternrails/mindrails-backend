package com.mindrails.backend.memory;

import com.mindrails.backend.agent.service.internal.TestSessionChat;

import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService;
import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.provider.Provider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class SessionMemoryLifecycleTest {
    @Test
    void independentSessionClientsAreRecreatedOnUpdatesAndReleasedOnDeletion() {
        var factory = TestLlmClients.create(List.of(), InMemoryChatMemoryStore::new, new ChatMemoryProperties());
        var registry = new SessionRegistryConfiguration().sessionRegistry();
        var sessions = new DefaultSessionService(
                registry);
        var streams = TestSessionChat.service(registry, factory, TestHistory.disabled(), TestMetrics.disabled());
        com.mindrails.backend.agent.service.internal.TestSessionFork.install(sessions, factory, streams);
        var firstModel = new RecordingModel();
        var secondModel = new RecordingModel();
        Provider firstProvider = provider(firstModel), secondProvider = provider(secondModel);
        var agents = new DefaultAgentService(new AgentRegistryConfiguration().agentRegistry(), sessions,
                List.of(firstProvider, secondProvider));
        UUID agentId = success(agents.create(Agent.builder().name("memory").systemMessage("Original").build()));
        UUID firstId = success(agents.startSession(agentId, firstProvider.getId()));
        UUID secondId = success(agents.startSession(agentId, firstProvider.getId()));
        Session first = registry.findManaged(firstId).orElseThrow(), second = registry.findManaged(secondId).orElseThrow();
        UUID memoryId = first.getMemoryId();
        assertNotEquals(memoryId, second.getMemoryId());
        assertNotEquals(memoryId, success(agents.get(agentId)).getMemoryId());
        success(TestSessionChat.chat(streams, firstId, "Remember this"));
        success(agents.updateSession(agentId, firstId, Session.builder().systemMessage("Updated").build(), secondProvider.getId()));
        assertEquals(memoryId, first.getMemoryId());
        success(TestSessionChat.chat(streams, firstId, "Continue"));
        assertEquals(SystemMessage.from("Updated"), secondModel.requests.getFirst().messages().getFirst());
        assertEquals(2, secondModel.requests.getFirst().messages().size());
        success(agents.updateSession(agentId, firstId, new Session(), secondProvider.getId()));
        success(TestSessionChat.chat(streams, firstId, "No system instruction"));
        assertTrue(secondModel.requests.getLast().messages().stream().noneMatch(SystemMessage.class::isInstance));
        assertEquals(1, secondModel.requests.getLast().messages().size());
        assertEquals(ServiceResult.ErrorCode.INVALID_ARGUMENT, failure(agents.updateSession(agentId, firstId,
                Session.builder().memoryId(second.getMemoryId()).build(), secondProvider.getId())));
        UUID forkId = success(agents.forkSession(agentId, firstId));
        Session fork = registry.findManaged(forkId).orElseThrow();
        assertNotEquals(memoryId, fork.getMemoryId());
        success(TestSessionChat.chat(streams, forkId, "Continue from the copied conversation"));
        success(agents.deleteSession(agentId, firstId));
        success(TestSessionChat.chat(streams, forkId, "Continue fork"));
        assertEquals(5, secondModel.requests.getLast().messages().size());
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "error", "complete"})
    void activeStreamAllowsSessionUpdatesAndCanStartAnotherRun(String ending) {
        var factory = TestLlmClients.create(List.of(), InMemoryChatMemoryStore::new, new ChatMemoryProperties());
        var registry = new SessionRegistryConfiguration().sessionRegistry();

        var rest = new DefaultSessionService(
                registry);
        var streams = TestSessionChat.service(registry, factory, TestHistory.disabled(), TestMetrics.disabled());
        var pending = new PendingModel();
        Provider streamingProvider = Provider.builder().streaming(true).streamingChatModel(pending).build();
        Session session = success(rest.startSession(Session.builder().id(UUID.randomUUID()).provider(streamingProvider).build()));
        success(TestSessionChat.start(streams, session.getId(), new ClientCommand.Chat(UUID.randomUUID(), "Old request"), event -> {}));
        TestSessionChat.await(() -> pending.handler != null);
        success(rest.updateSession(session.getId(), Session.builder().provider(streamingProvider).build()));
        var handle = mock(StreamingHandle.class);
        pending.handler.onPartialResponse(new PartialResponse("old"), new PartialResponseContext(handle));
        switch (ending) {
            case "cancel" -> {
                success(streams.stop(session.getId()));
                verify(handle).cancel();
            }
            case "error" -> pending.handler.onError(new IllegalStateException("Provider failed"));
            case "complete" -> pending.handler.onCompleteResponse(reply());
            default -> throw new AssertionError(ending);
        }
        success(rest.updateSession(session.getId(), Session.builder().provider(provider(new RecordingModel())).build()));
        success(TestSessionChat.chat(streams, session.getId(), "New request"));
        success(TestSessionChat.chat(streams, session.getId(), "Still usable"));
    }

    @Test
    void configuredWindowClipsMessagesAcrossSynchronousTurns() {
        var store = new InMemoryChatMemoryStore();
        var properties = new ChatMemoryProperties();
        properties.setMaxMessages(3);
        var factory = TestLlmClients.create(List.of(), () -> store, properties);
        var registry = new SessionRegistryConfiguration().sessionRegistry();

        var rest = new DefaultSessionService(
                registry);
        var streams = TestSessionChat.service(registry, factory, TestHistory.disabled(), TestMetrics.disabled());
        UUID id = UUID.randomUUID();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                return reply();
            }
        };
        Session session = success(rest.startSession(Session.builder().id(id).provider(provider(model)).build()));
        for (int turn = 0; turn < 4; turn++) success(TestSessionChat.chat(streams, id, "Turn " + turn));
        assertEquals(3, store.getMessages(session.getMemoryId()).size());
    }

    @Test
    void successfulStreamingTurnsReuseTheSessionClientAndItsMemory() {
        var factory = TestLlmClients.create(List.of(), InMemoryChatMemoryStore::new, new ChatMemoryProperties());
        var registry = new SessionRegistryConfiguration().sessionRegistry();
        var requests = new ArrayList<ChatRequest>();
        StreamingChatModel model = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
                handler.onCompleteResponse(reply());
            }
        };
        var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .provider(Provider.builder().streaming(true).streamingChatModel(model).build()).build();
        registry.insertIfAbsent(session);
        var streams = TestSessionChat.service(registry, factory, TestHistory.disabled(), TestMetrics.disabled());
        success(TestSessionChat.chat(streams, session.getId(), "First"));
        success(TestSessionChat.chat(streams, session.getId(), "Second"));
        assertEquals(3, requests.getLast().messages().size());
    }

    static Provider provider(ChatModel model) {
        return Provider.builder().id(UUID.randomUUID()).streaming(false).chatModel(model).build();
    }

    static ChatResponse reply() {
        return ChatResponse.builder().aiMessage(AiMessage.from("[\"reply\"]")).build();
    }

    static <T> T success(ServiceResult<T> result) {
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<T>) result).value();
    }

    static ServiceResult.ErrorCode failure(ServiceResult<?> result) {
        return assertInstanceOf(ServiceResult.Failure.class, result).code();
    }

    static class RecordingModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        @Override public ChatResponse doChat(ChatRequest request) {
            requests.add(request);
            return reply();
        }
    }

    static class PendingModel implements StreamingChatModel {
        StreamingChatResponseHandler handler;
        @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
            this.handler = handler;
        }
    }
}
