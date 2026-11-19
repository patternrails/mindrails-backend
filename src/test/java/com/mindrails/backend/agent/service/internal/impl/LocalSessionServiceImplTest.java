package com.mindrails.backend.agent.service.internal.impl;

import com.mindrails.backend.common.config.FileStorageProperties;

import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.TestHistory;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.agent.service.internal.llm.listener.AiServiceListenerFactory;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.skill.SkillCatalog;
import com.mindrails.backend.skill.SkillFactory;
import com.mindrails.backend.tool.CompositeToolConfiguration;
import com.mindrails.backend.tool.spec.LsTool;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.agent.tool.ToolSpecifications;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.Result;
import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.slf4j.LoggerFactory;

class LocalSessionServiceImplTest {

    @Test
    void storesTheSuppliedSessionWithoutAssemblingIt() {
        DefaultSessionService service = new DefaultSessionService(
                new SessionRegistryConfiguration().sessionRegistry());
        UUID agentId = UUID.randomUUID();
        ToolSpecification toolSpecification = ToolSpecifications.toolSpecificationsFrom(new LsTool()).getFirst();
        UUID skillId = UUID.randomUUID();
        UUID memoryId = UUID.randomUUID();
        UUID knowledgeBaseId = UUID.randomUUID();
        Provider provider = Provider.builder().name("test-provider").build();
        Instant now = Instant.now();
        Session session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).parentAgentId(agentId).provider(provider)
                .systemMessage("Compare options before booking")
                .memoryId(memoryId)
                .createdAt(now).updatedAt(now).build();

        ServiceResult<Session> result = service.startSession(session);

        assertInstanceOf(ServiceResult.Success.class, result);
        assertSame(session, value(result));
        assertNotNull(session.getId());
        assertEquals(agentId, session.getParentAgentId());
        assertSame(provider, session.getProvider());
        assertEquals("Compare options before booking", session.getSystemMessage());
        assertEquals(memoryId, session.getMemoryId());
        assertEquals(now, session.getCreatedAt());
        assertEquals(session.getCreatedAt(), session.getUpdatedAt());
        assertEquals(List.of(session), value(service.listSessions(agentId, 0, 20)));
        Session copy = session.clone();
        assertNotSame(session, copy);
        assertEquals(session, copy);
        assertSame(session.getProvider(), copy.getProvider());

        assertFailure(service.startSession(session), ErrorCode.CONFLICT);
    }

    @Test
    void requiresIdAndProviderBeforeStoringSession() {
        DefaultSessionService service = new DefaultSessionService(
                new SessionRegistryConfiguration().sessionRegistry());

        ServiceResult<Session> result = service.startSession(Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).build());

        assertInstanceOf(ServiceResult.Failure.class, result);
        assertEquals(ErrorCode.INVALID_ARGUMENT, ((ServiceResult.Failure<Session>) result).code());
        assertFailure(service.startSession(Session.builder().provider(Provider.builder().build()).build()),
                ErrorCode.INVALID_ARGUMENT);
        assertFailure(service.startSession(null), ErrorCode.INVALID_ARGUMENT);
    }

    @Test
    void listsUpdatesForksAndDeletesSessionsWithoutChangingAgents() {
        DefaultSessionService service = new DefaultSessionService(
                new SessionRegistryConfiguration().sessionRegistry());
        com.mindrails.backend.agent.service.internal.TestSessionFork.install(service);
        UUID firstAgentId = UUID.randomUUID();
        UUID secondAgentId = UUID.randomUUID();
        Provider originalProvider = Provider.builder().name("original").build();
        Provider replacementProvider = Provider.builder().name("replacement").build();
        Session original = value(service.startSession(newSession(firstAgentId, originalProvider)));
        value(service.startSession(newSession(firstAgentId, originalProvider)));
        Session other = value(service.startSession(newSession(secondAgentId, originalProvider)));
        UUID firstId = original.getId();

        List<Session> all = value(service.listSessions(firstAgentId, 0, 20));
        assertEquals(2, all.size());
        assertEquals(all.subList(0, 1), value(service.listSessions(firstAgentId, 0, 1)));
        assertEquals(all.subList(1, 2), value(service.listSessions(firstAgentId, 1, 1)));
        assertEquals(List.of(other.getId()), value(service.listSessions(secondAgentId, 0, 20)).stream()
                .map(Session::getId).toList());
        assertFailure(service.listSessions(firstAgentId, -1, 20), ErrorCode.INVALID_ARGUMENT);

        Instant createdAt = original.getCreatedAt();
        UUID skillId = UUID.randomUUID();
        UUID memoryId = original.getMemoryId();
        UUID knowledgeBaseId = UUID.randomUUID();
        Session changes = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).parentAgentId(secondAgentId)
                .provider(replacementProvider)
                .systemMessage("Updated")
                .skillIds(List.of(skillId))
                .memoryId(memoryId).build();
        value(service.updateSession(firstId, changes));
        assertEquals(firstId, original.getId());
        assertEquals(firstAgentId, original.getParentAgentId());
        assertEquals(createdAt, original.getCreatedAt());
        assertFalse(original.getUpdatedAt().isBefore(createdAt));
        assertSame(replacementProvider, original.getProvider());
        assertEquals("Updated", original.getSystemMessage());
        assertTrue(original.getSkillIds().isEmpty(), "Legacy skill selections no longer change Session settings");
        assertEquals(memoryId, original.getMemoryId());

        Session forked = value(service.forkSession(firstId));
        assertNotEquals(firstId, forked.getId());
        assertEquals(firstAgentId, forked.getParentAgentId());
        assertSame(replacementProvider, forked.getProvider());
        assertEquals(original.getSystemMessage(), forked.getSystemMessage());
        assertEquals(original.getSkillIds(), forked.getSkillIds());

        assertFailure(service.updateSession(UUID.randomUUID(), changes), ErrorCode.NOT_FOUND);
        assertFailure(service.forkSession(UUID.randomUUID()), ErrorCode.NOT_FOUND);
        assertFailure(service.deleteSession(UUID.randomUUID()), ErrorCode.NOT_FOUND);
        value(service.deleteSession(firstId));
        assertEquals(2, value(service.listSessions(firstAgentId, 0, 20)).size());
        assertFailure(service.deleteSession(firstId), ErrorCode.NOT_FOUND);
    }

    @Test
    void clientFactoryCreatesFreshClientsAndRejectsUnconfiguredProviders() {
        LlmClientFactory factory = TestMemory.factory(List.of());
        Provider provider = Provider.builder().chatModel(mock(ChatModel.class)).build();
        Session first = newSession(UUID.randomUUID(), provider);
        Session second = newSession(UUID.randomUUID(), provider);

        LlmClient firstClient = value(factory.createClientForLoop(first));

        assertNotSame(firstClient, value(factory.createClientForLoop(first)));
        assertNotSame(firstClient, value(factory.createClientForLoop(second)));
        assertFailure(factory.createClientForLoop(newSession(UUID.randomUUID(), Provider.builder().build())),
                ErrorCode.NOT_IMPLEMENTED);
    }

    @Test
    void clientFactoryIncludesConfiguredToolsInChatRequests() {
        ChatModel model = new ChatModel() {
            @Override
            public ChatResponse doChat(ChatRequest request) {
                var specifications = request.parameters().toolSpecifications();
                assertEquals(8, specifications.size());
                assertEquals(Set.of("ls", "read", "write", "edit", "bash", "powershell", "grep", "find"),
                        specifications.stream().map(ToolSpecification::name).collect(Collectors.toSet()));
                return ChatResponse.builder().aiMessage(AiMessage.from("[\"done\"]")).build();
            }
        };
        var metrics = TestMetrics.disabled();
        var tools = new CompositeToolConfiguration().tools(
                new org.springframework.beans.factory.support.StaticListableBeanFactory()
                        .getBeanProvider(com.mindrails.backend.tool.IdentifiedTool.class), metrics);
        var factory = new LlmClientFactory(tools, com.mindrails.backend.memory.TestMemory.inMemory(100),
                new SkillCatalog(new SkillFactory(new FileStorageProperties(java.nio.file.Path.of("build", "test-storage")))), new AiServiceListenerFactory(TestHistory.disabled(), metrics),
                com.mindrails.backend.agent.service.internal.llm.TestLlmClients.noMcpTools(),
                com.mindrails.backend.agent.service.internal.llm.TestLlmClients.disabledKnowledgeTools(),
                new com.mindrails.backend.agent.service.internal.llm.ConversationRag(List.of(), Integer.MAX_VALUE, TestHistory.disabled(), null),
                new com.mindrails.backend.tool.ToolErrorHandler(),
                new com.mindrails.backend.asset.tool.SessionDeliverableToolAdapter(TestLlmClients.sessionFiles()),
                new com.mindrails.backend.asset.tool.TaskDeliverableToolAdapter(null),
                        null, null);
        Session session = newSession(UUID.randomUUID(), Provider.builder().chatModel(model).build());
        session.setMemoryId(UUID.randomUUID());

        assertEquals("[\"done\"]", TestLlmClients.chat(value(factory.createClientForLoop(session)), TestMemory.ID, "Hello").content());
    }

    private Session newSession(UUID agentId, Provider provider) {
        Instant now = Instant.now();
        return Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).parentAgentId(agentId).provider(provider)
                .createdAt(now).updatedAt(now).build();
    }

    private <T> T value(ServiceResult<T> result) {
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<T>) result).value();
    }

    private <T> void assertFailure(ServiceResult<T> result, ErrorCode code) {
        assertInstanceOf(ServiceResult.Failure.class, result);
        assertEquals(code, ((ServiceResult.Failure<T>) result).code());
    }
}
