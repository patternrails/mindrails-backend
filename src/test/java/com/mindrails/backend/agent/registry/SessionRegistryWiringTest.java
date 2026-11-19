package com.mindrails.backend.agent.registry;

import static org.mockito.ArgumentMatchers.*;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.RETURNS_SELF;

import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.service.AgentService;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway;
import com.mindrails.backend.agent.service.DefaultAgentSessionChatStreamGateway;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.provider.Provider;
import dev.langchain4j.service.TokenStream;
import java.lang.reflect.Field;
import java.util.List;
import java.util.ArrayList;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.boot.test.context.TestConfiguration;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.provider.ProviderTestConfiguration;
import org.springframework.test.context.junit.jupiter.SpringJUnitConfig;

@SpringJUnitConfig(SessionRegistryWiringTest.Wiring.class)
class SessionRegistryWiringTest {
    @TestConfiguration(proxyBeanMethods = false)
    @Import({ProviderTestConfiguration.class, DefaultAgentService.class, DefaultSessionService.class,
            DefaultAgentSessionChatStreamGateway.class, DefaultLoopSessionStreamService.class})
    static class Wiring {
        @Bean AgentRegistry agentRegistry() { return new AgentRegistryConfiguration().agentRegistry(); }
        @Bean SessionRegistry sessionRegistry() { return new SessionRegistryConfiguration().sessionRegistry(); }
        @Bean ChatHistory chatHistory() { return TestHistory.disabled(); }
        @Bean MetricsRecorder metricsRecorder() { return TestMetrics.disabled(); }
        }


    @org.springframework.test.context.bean.override.mockito.MockitoBean
    com.mindrails.backend.agent.service.internal.llm.LlmClientFactory clientFactory;
    @Autowired SessionRegistry writer;
    @Autowired ReadOnlySessionRegistry reader;
    @Autowired AgentRegistry agentWriter;
    @Autowired ReadOnlyAgentRegistry agentReader;
    @Autowired AgentService agents;
    @Autowired AgentSessionChatStreamGateway gateway;
    @Autowired @Qualifier("providers") List<Provider> providers;

    @Test
    void bothRegistryBeansAreSharedAndGatewayUsesOnlyReadOnlyInterfaces() throws Exception {
        assertSame(writer, reader);
        assertSame(agentWriter, agentReader);
        Field agentField = DefaultAgentSessionChatStreamGateway.class.getDeclaredField("agents");
        Field sessionField = DefaultAgentSessionChatStreamGateway.class.getDeclaredField("sessions");
        assertSame(ReadOnlyAgentRegistry.class, agentField.getType());
        assertSame(ReadOnlySessionRegistry.class, sessionField.getType());

        UUID agentId = ((ServiceResult.Success<UUID>) agents.create(Agent.builder().name("registry-owner").build())).value();
        Provider streamingProvider = providers.stream().filter(provider -> Boolean.TRUE.equals(provider.getStreaming()))
                .findFirst().orElseThrow();
        UUID sessionId = ((ServiceResult.Success<UUID>) agents.startSession(agentId, streamingProvider.getId())).value();
        var scope = new AgentSessionChatStreamGateway.AgentSessionScope(agentId, sessionId);

        assertEquals(agentId, reader.find(sessionId).orElseThrow().parentAgentId());
        assertTrue(agentReader.find(agentId).orElseThrow().sessionIds().contains(sessionId));
        assertTrue(gateway.validate(scope) instanceof ServiceResult.Success<?>);
        LlmClient client = mock(LlmClient.class);
        TokenStream tokenStream = mock(TokenStream.class, RETURNS_SELF);
        when(client.chatWithStream(any(UUID.class), eq("Hello"), any(dev.langchain4j.invocation.InvocationParameters.class))).thenReturn(tokenStream);
        when(clientFactory.createClientForLoop(any())).thenReturn(ServiceResult.success(client));
        List<ChatStreamEvent> events = new java.util.concurrent.CopyOnWriteArrayList<>();
        UUID requestId = UUID.randomUUID();
        assertTrue(gateway.command(scope, AgentSessionChatStreamGateway.CommandType.BIND, (AgentSessionChatStreamGateway.EventSink) events::add) instanceof ServiceResult.Success<?>);
        assertTrue(gateway.chat(scope, new ClientCommand.Chat(requestId, "Hello"))
                instanceof ServiceResult.Success<?>);
        verify(tokenStream, org.mockito.Mockito.timeout(3000)).start();
        assertEquals(1, events.size());
        var started = org.junit.jupiter.api.Assertions.assertInstanceOf(ChatStreamEvent.Started.class, events.getFirst());
        assertEquals(requestId, started.requestId());
        assertEquals("Hello", started.input().message());
        assertTrue(reader.find(sessionId).isPresent());

        UUID anotherAgent = ((ServiceResult.Success<UUID>) agents.create(Agent.builder().name("other-owner").build())).value();
        assertEquals(ErrorCode.NOT_FOUND, ((ServiceResult.Failure<?>) gateway.validate(
                new AgentSessionChatStreamGateway.AgentSessionScope(anotherAgent, sessionId))).code());

        gateway.command(scope, AgentSessionChatStreamGateway.CommandType.STOP, null);
        agents.deleteSession(agentId, sessionId);
        assertTrue(reader.find(sessionId).isEmpty());
        assertFalse(agentReader.find(agentId).orElseThrow().sessionIds().contains(sessionId));
        assertFalse(gateway.validate(scope) instanceof ServiceResult.Success<?>);
    }
}
