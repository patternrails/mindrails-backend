package com.mindrails.backend.persistence;

import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.TestHistory;
import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.provider.Provider;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(com.mindrails.backend.agent.service.internal.TestSessionChat.class)
class InMemoryCascadeTest {
    @Test
    void cascadeRemovesSessionsAndReleasesClients() {
        var agents = new AgentRegistryConfiguration().agentRegistry();
        var sessions = new SessionRegistryConfiguration().sessionRegistry();
        var factory = TestMemory.factory(List.of());

        var provider = Provider.builder().id(UUID.randomUUID()).streaming(false).chatModel(new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                return ChatResponse.builder().aiMessage(AiMessage.from("[\"ok\"]")).build();
            }
        }).build();
        var service = new DefaultAgentService(agents, new DefaultSessionService(
                sessions), List.of(provider));
        var loops = com.mindrails.backend.agent.service.internal.TestSessionChat.service(sessions, factory, TestHistory.disabled(), TestMetrics.disabled());
        UUID id = success(service.create(Agent.builder().name("Cascade").build()));
        UUID firstId = success(service.startSession(id, provider.getId()));
        UUID secondId = success(service.startSession(id, provider.getId()));
        success(com.mindrails.backend.agent.service.internal.TestSessionChat.chat(loops, firstId, "First"));
        success(com.mindrails.backend.agent.service.internal.TestSessionChat.chat(loops, secondId, "Second"));
        var first = sessions.findManaged(firstId).orElseThrow();
        var second = sessions.findManaged(secondId).orElseThrow();
        var firstClient = success(factory.createClientForLoop(first));
        var secondClient = success(factory.createClientForLoop(second));
        success(service.delete(id));
        assertTrue(agents.find(id).isEmpty());
        assertTrue(sessions.listAllManaged(id).isEmpty());
        assertNotSame(firstClient, success(factory.createClientForLoop(first)));
        assertNotSame(secondClient, success(factory.createClientForLoop(second)));
    }

    static <T> T success(ServiceResult<T> result) {
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<T>) result).value();
    }
}
