package com.mindrails.backend.memory.agent;

import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.agent.model.Agent;
import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.ChatMemoryProperties;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.provider.SnapshotProviderRegistry;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

final class AgentMemoryTestSupport {
    static class Fixture {
        final AgentRegistry agentRegistry = new AgentRegistryConfiguration().agentRegistry();
        final SessionRegistry sessionRegistry = new SessionRegistryConfiguration().sessionRegistry();
        final AgentMemoryProperties properties = new AgentMemoryProperties();
        volatile InMemoryChatMemoryStore chatStore = new InMemoryChatMemoryStore();

        final RecordingModel model = new RecordingModel();
        final Provider provider = Provider.builder().id(UUID.randomUUID()).streaming(false).chatModel(model).build();
        final AgentMemoryService memory;
        final LlmClientFactory factory;
        final DefaultSessionService sessions;
        final DefaultAgentService agents;
        final com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService streams;

        Fixture() { this(new InMemoryAgentMemoryStore()); }
        Fixture(AgentMemoryStore store) { this(store, List.of()); }
        Fixture(AgentMemoryStore store, List<com.mindrails.backend.tool.IdentifiedTool> tools) {

            memory = new AgentMemoryService(agentRegistry, sessionRegistry, store, properties);
            factory = TestLlmClients.create(tools, () -> chatStore = new InMemoryChatMemoryStore(), new ChatMemoryProperties(), memory);
            streams = com.mindrails.backend.agent.service.internal.TestSessionChat.service(
                    sessionRegistry, factory, TestHistory.disabled(), TestMetrics.disabled());
            sessions = new DefaultSessionService(
                    sessionRegistry);
            com.mindrails.backend.agent.service.internal.TestSessionFork.install(sessions, factory, streams);
            agents = new DefaultAgentService(agentRegistry, sessions, new SnapshotProviderRegistry(List.of(provider)), memory);
        }

        UUID agent() { return success(agents.create(Agent.builder().name("Memory owner").systemMessage("Literal {{instruction}}").build())); }
        UUID session(UUID agent) { return success(agents.startSession(agent, provider.getId())); }
    }

    static AgentMemoryInput input(String key, String content, boolean pinned, Long version) {
        return new AgentMemoryInput(key, AgentMemoryEntry.Type.FACT, content, List.of(), pinned, 0, null, version);
    }
    static <T> T success(ServiceResult<T> result) {
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<T>) result).value();
    }
    static ServiceResult.ErrorCode failure(ServiceResult<?> result) {
        return assertInstanceOf(ServiceResult.Failure.class, result).code();
    }
    static ChatResponse reply() { return ChatResponse.builder().aiMessage(AiMessage.from("[\"reply\"]")).build(); }
    static ChatResponse call(String name, String args) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString()).name(name).arguments(args).build())).build();
    }
    static tools.jackson.databind.JsonNode lastTool(ChatRequest request) {
        var text = request.messages().stream().filter(ToolExecutionResultMessage.class::isInstance)
                .map(ToolExecutionResultMessage.class::cast).toList().getLast().text();
        return tools.jackson.databind.json.JsonMapper.builder().build().readTree(text);
    }
    static String lastUser(ChatRequest request) {
        return request.messages().stream().filter(UserMessage.class::isInstance).map(UserMessage.class::cast)
                .toList().getLast().singleText();
    }
    static class RecordingModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        @Override public ChatResponse doChat(ChatRequest request) { requests.add(request); return reply(); }
    }
}
