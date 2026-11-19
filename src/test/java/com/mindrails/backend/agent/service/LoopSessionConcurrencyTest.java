package com.mindrails.backend.agent.service;

import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.TestHistory;
import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.provider.Provider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(com.mindrails.backend.agent.service.internal.TestSessionChat.class)
class LoopSessionConcurrencyTest {

    @Test
    void slowChatAllowsSessionUpdatesAndDeletionWithoutAnExecutionCoordinator() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                if (request.messages().getLast().toString().contains("slow request")) {
                    entered.countDown();
                    try {
                        if (!release.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("Test timed out");
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException(error);
                    }
                }
                return reply();
            }
        };
        Provider provider = Provider.builder().id(UUID.randomUUID()).streaming(false).chatModel(model).build();
        DefaultAgentService service = service(provider);
        var loops = currentLoops;
        UUID agentId = value(service.create(Agent.builder().name("First").build()));
        UUID slowSessionId = value(service.startSession(agentId, provider.getId()));

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var slowChat = executor.submit(() -> com.mindrails.backend.agent.service.internal.TestSessionChat.chat(loops, slowSessionId, "slow request"));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                executor.submit(() -> {
                    assertEquals(1, value(service.list(0, 20)).size());
                    assertEquals("First", value(service.get(agentId)).getName());
                    value(service.update(agentId, Agent.builder().name("Updated").build()));
                    UUID otherAgentId = value(service.create(Agent.builder().name("Other").build()));
                    value(service.delete(otherAgentId));
                    assertEquals(1, value(service.listSessions(agentId, 0, 20)).size());
                    UUID otherSessionId = value(service.startSession(agentId, provider.getId()));
                    value(service.updateSession(agentId, otherSessionId, Session.builder().build(), provider.getId()));
                    UUID forkId = value(service.forkSession(agentId, otherSessionId));
                    assertEquals("[\"reply\"]", value(com.mindrails.backend.agent.service.internal.TestSessionChat.chat(loops, otherSessionId, "fast request")).answer());
                    value(service.deleteSession(agentId, otherSessionId));
                    value(service.deleteSession(agentId, forkId));
                    value(service.updateSession(agentId, slowSessionId, new Session(), provider.getId()));
                    value(service.deleteSession(agentId, slowSessionId));
                }).get(3, TimeUnit.SECONDS);
                assertFalse(slowChat.isDone());
            } finally {
                release.countDown();
            }
            assertEquals("[\"reply\"]", value(slowChat.get(3, TimeUnit.SECONDS)).answer());
            assertEquals(ErrorCode.NOT_FOUND,
                    assertInstanceOf(ServiceResult.Failure.class, com.mindrails.backend.agent.service.internal.TestSessionChat.chat(loops, slowSessionId, "next request")).code());
        }
    }

    @Test
    void modelFailureReleasesTheSessionForAnotherChatAndConfigurationChanges() {
        AtomicInteger calls = new AtomicInteger();
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                if (calls.getAndIncrement() == 0) throw new IllegalStateException("Model unavailable");
                return reply();
            }
        };
        Provider provider = Provider.builder().id(UUID.randomUUID()).streaming(false).chatModel(model).build();
        DefaultAgentService service = service(provider);
        var loops = currentLoops;
        UUID agentId = value(service.create(Agent.builder().name("First").build()));
        UUID sessionId = value(service.startSession(agentId, provider.getId()));

        assertInstanceOf(ServiceResult.Failure.class, com.mindrails.backend.agent.service.internal.TestSessionChat.chat(loops, sessionId, "Hello"));
        assertEquals("[\"reply\"]", value(com.mindrails.backend.agent.service.internal.TestSessionChat.chat(loops, sessionId, "Retry")).answer());
        value(service.updateSession(agentId, sessionId, new Session(), provider.getId()));
        value(service.deleteSession(agentId, sessionId));
    }

    private com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService currentLoops;

    private DefaultAgentService service(Provider provider) {
        var sessions = new SessionRegistryConfiguration().sessionRegistry();
        var factory = TestMemory.factory(List.of());
        currentLoops = com.mindrails.backend.agent.service.internal.TestSessionChat.service(sessions, factory, TestHistory.disabled(), TestMetrics.disabled());
        return new DefaultAgentService(new AgentRegistryConfiguration().agentRegistry(),
                com.mindrails.backend.agent.service.internal.TestSessionFork.install(new DefaultSessionService(sessions), factory, currentLoops), List.of(provider));
    }

    private static ChatResponse reply() {
        return ChatResponse.builder().aiMessage(AiMessage.from("[\"reply\"]")).build();
    }

    private <T> T value(ServiceResult<T> result) {
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<T>) result).value();
    }

    private void assertConflict(ServiceResult<?> result) {
        assertInstanceOf(ServiceResult.Failure.class, result);
        assertEquals(ErrorCode.CONFLICT, ((ServiceResult.Failure<?>) result).code());
    }
}
