package com.mindrails.backend.agent.service.internal.llm;

import com.mindrails.backend.agent.registry.ReadOnlySessionRegistry.SessionView;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.asset.service.SessionDeliverables;
import com.mindrails.backend.asset.tool.SessionDeliverableToolAdapter;
import com.mindrails.backend.asset.tool.TaskDeliverableToolAdapter;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.agent.service.internal.llm.listener.AiServiceListenerFactory;
import com.mindrails.backend.knowledge.TestKnowledge;
import com.mindrails.backend.mcp.api.toolspec.McpTools;
import com.mindrails.backend.memory.ChatMemoryStoreFactory;
import com.mindrails.backend.memory.ChatMemoryProperties;
import com.mindrails.backend.memory.agent.AgentMemoryService;
import com.mindrails.backend.skill.SkillCatalog;
import com.mindrails.backend.skill.SkillFactory;
import com.mindrails.backend.tool.IdentifiedTool;
import com.mindrails.backend.memory.agent.AgentMemoryRetriever;
import com.mindrails.backend.tool.ToolErrorHandler;
import com.mindrails.backend.tool.spec.AgentMemoryTools;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.service.tool.*;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import org.springframework.jdbc.core.JdbcTemplate;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Test-only defaults; production has one dependency-injection constructor. */
public final class TestLlmClients {
    private TestLlmClients() {}

    public static SessionDeliverables sessionFiles() {
        return sessionFiles(testStorage());
    }

    private static SessionDeliverables sessionFiles(Path storage) {
        var properties = new AssetStorageProperties(new FileStorageProperties(storage));
        return new SessionDeliverables(properties, id -> Optional.of(new SessionView(id, null, null, false)));
    }

    private static Path testStorage() {
        return Path.of(System.getProperty("java.io.tmpdir"), "mindrails-session-tool-tests",
                UUID.randomUUID().toString());
    }

    /** Await the same TokenStream API for both Session provider modes. */
    public static dev.langchain4j.service.Result<String> chat(
            com.mindrails.backend.agent.model.LlmClient client, UUID memoryId, String message) {
        return chat(client, memoryId, message, null);
    }

    public static dev.langchain4j.service.Result<String> chat(
            com.mindrails.backend.agent.model.LlmClient client, UUID memoryId, String message,
            dev.langchain4j.invocation.InvocationParameters parameters) {
        var completed = new java.util.concurrent.CompletableFuture<dev.langchain4j.model.chat.response.ChatResponse>();
        var sources = new java.util.concurrent.CopyOnWriteArrayList<dev.langchain4j.rag.content.Content>();
        var tools = new java.util.concurrent.CopyOnWriteArrayList<ToolExecution>();
        var stream = parameters == null ? client.chatWithStream(memoryId, message)
                : client.chatWithStream(memoryId, message, parameters);
        stream.onRetrieved(sources::addAll).onToolExecuted(tools::add)
                .onCompleteResponse(completed::complete).onError(completed::completeExceptionally).start();
        try {
            var response = completed.get(5, java.util.concurrent.TimeUnit.SECONDS);
            return dev.langchain4j.service.Result.<String>builder().content(response.aiMessage().text())
                    .tokenUsage(response.tokenUsage()).finishReason(response.finishReason())
                    .sources(sources).toolExecutions(tools).build();
        } catch (java.util.concurrent.ExecutionException failure) {
            if (failure.getCause() instanceof RuntimeException error) throw error;
            if (failure.getCause() instanceof Error error) throw error;
            throw new AssertionError(failure.getCause());
        } catch (InterruptedException failure) {
            Thread.currentThread().interrupt();
            throw new AssertionError(failure);
        } catch (java.util.concurrent.TimeoutException failure) { throw new AssertionError("Session stream did not finish", failure); }
    }

    public static McpTools noMcpTools() {
        var tools = org.mockito.Mockito.mock(McpTools.class);
        org.mockito.Mockito.when(tools.provideTools(org.mockito.ArgumentMatchers.any()))
                .thenReturn(ToolProviderResult.builder().build());
        return tools;
    }

    public static com.mindrails.backend.knowledge.tool.KnowledgeTools disabledKnowledgeTools() {
        var tools = org.mockito.Mockito.mock(com.mindrails.backend.knowledge.tool.KnowledgeTools.class);
        org.mockito.Mockito.when(tools.provideTools(org.mockito.ArgumentMatchers.any()))
                .thenReturn(ToolProviderResult.builder().build());
        return tools;
    }

    public static LlmClientFactory create(
            List<IdentifiedTool> tools, java.util.function.Supplier<dev.langchain4j.store.memory.chat.ChatMemoryStore> stores, ChatMemoryProperties properties) {
        return create(tools, stores, properties, null, null, null, null);
    }

    public static LlmClientFactory create(
            List<IdentifiedTool> tools,
            java.util.function.Supplier<dev.langchain4j.store.memory.chat.ChatMemoryStore> stores,
            ChatMemoryProperties properties,
            AgentMemoryService memory) {
        return create(tools, stores, properties, memory, null, null, null);
    }

    public static LlmClientFactory create(
            List<IdentifiedTool> tools,
            java.util.function.Supplier<dev.langchain4j.store.memory.chat.ChatMemoryStore> stores,
            ChatMemoryProperties properties,
            AgentMemoryService memory,
            McpTools mcp) {
        return create(tools, stores, properties, memory, mcp, null, null);
    }

    public static LlmClientFactory create(
            List<IdentifiedTool> tools,
            java.util.function.Supplier<dev.langchain4j.store.memory.chat.ChatMemoryStore> stores,
            ChatMemoryProperties properties,
            AgentMemoryService memory,
            McpTools mcp,
            Path storage) {
        return create(tools, stores, properties, memory, mcp, storage, null);
    }

    public static LlmClientFactory create(
            List<IdentifiedTool> tools,
            java.util.function.Supplier<dev.langchain4j.store.memory.chat.ChatMemoryStore> stores,
            ChatMemoryProperties properties,
            AgentMemoryService memory,
            McpTools mcp,
            Path storage,
            TestKnowledge knowledge) {
        return create(tools, stores, properties, memory, mcp, storage, knowledge,
                com.mindrails.backend.history.TestHistory.disabled(), com.mindrails.backend.metrics.TestMetrics.disabled());
    }

    public static LlmClientFactory create(List<IdentifiedTool> tools, java.util.function.Supplier<dev.langchain4j.store.memory.chat.ChatMemoryStore> stores,
            ChatMemoryProperties properties, AgentMemoryService memory, McpTools mcp, Path storage,
            TestKnowledge knowledge, ChatHistory chatHistory,
            com.mindrails.backend.metrics.MetricsRecorder metricsRecorder) {
        return wire(tools, stores, properties, null, memory, mcp, storage, knowledge, chatHistory, metricsRecorder);
    }

    public static LlmClientFactory configured(List<IdentifiedTool> tools, ChatMemoryProperties properties, JdbcTemplate jdbc) {
        return wire(tools, null, properties, jdbc, null, null, null, null,
                com.mindrails.backend.history.TestHistory.disabled(), com.mindrails.backend.metrics.TestMetrics.disabled());
    }

    public static LlmClientFactory withCapabilities(
            List<IdentifiedTool> tools,
            SessionDeliverableToolAdapter sessionDeliverables,
            TaskDeliverableToolAdapter taskDeliverables) {
        return wire(tools, InMemoryChatMemoryStore::new, new ChatMemoryProperties(), null,
                null, null, null, null, com.mindrails.backend.history.TestHistory.disabled(),
                com.mindrails.backend.metrics.TestMetrics.disabled(),
                sessionDeliverables, taskDeliverables);
    }

    private static LlmClientFactory wire(List<IdentifiedTool> tools, java.util.function.Supplier<ChatMemoryStore> stores,
            ChatMemoryProperties properties, JdbcTemplate jdbc, AgentMemoryService memory, McpTools mcp,
            Path storage, TestKnowledge knowledge, ChatHistory chatHistory,
            com.mindrails.backend.metrics.MetricsRecorder metricsRecorder) {
        Path directory = storage == null ? testStorage() : storage;
        return wire(tools, stores, properties, jdbc, memory, mcp, directory, knowledge, chatHistory,
                metricsRecorder, new SessionDeliverableToolAdapter(sessionFiles(directory)), new TaskDeliverableToolAdapter(null));
    }

    private static LlmClientFactory wire(List<IdentifiedTool> tools, java.util.function.Supplier<ChatMemoryStore> stores,
            ChatMemoryProperties properties, JdbcTemplate jdbc, AgentMemoryService memory, McpTools mcp,
            Path storage, TestKnowledge knowledge, ChatHistory chatHistory,
            com.mindrails.backend.metrics.MetricsRecorder metricsRecorder,
            SessionDeliverableToolAdapter sessionDeliverables, TaskDeliverableToolAdapter taskDeliverables) {
        var clientTools = new ArrayList<IdentifiedTool>(tools);
        var retrievers = new ArrayList<ContentRetriever>();
        if (memory != null) {
            clientTools.add(new AgentMemoryTools(memory));
            retrievers.add(new AgentMemoryRetriever(memory));
        }
        if (knowledge != null) retrievers.add(knowledge.retriever());
        var activeStores = new java.util.concurrent.ConcurrentHashMap<UUID, ChatMemoryStore>();
        var restoredStores = new java.util.concurrent.ConcurrentHashMap<UUID, ChatMemoryStore>();
        var chatMemoryStoreFactory = new ChatMemoryStoreFactory(
                properties, jdbc == null ? org.mockito.Mockito.mock(JdbcTemplate.class) : jdbc) {
            @Override public ChatMemory buildChatMemory(UUID memoryId) {
                if (stores == null) return super.buildChatMemory(memoryId);
                ChatMemoryStore store = restoredStores.remove(memoryId);
                if (store == null) store = stores.get();
                activeStores.put(memoryId, store);
                return MessageWindowChatMemory.builder()
                        .id(memoryId)
                        .maxMessages(properties.getMaxMessages())
                        .alwaysKeepSystemMessageFirst(true)
                        .chatMemoryStore(store)
                        .build();
            }
            @Override public List<dev.langchain4j.data.message.ChatMessage> snapshot(UUID id) {
                if (stores == null) return super.snapshot(id);
                var store = activeStores.get(id);
                return store == null ? List.of() : List.copyOf(store.getMessages(id));
            }
            @Override public void restore(UUID id, List<dev.langchain4j.data.message.ChatMessage> messages) {
                if (stores == null) { super.restore(id, messages); return; }
                var store = stores.get(); store.updateMessages(id, messages);
                activeStores.put(id, store); restoredStores.put(id, store);
            }
            @Override public void delete(UUID id) {
                if (stores == null) { super.delete(id); return; }
                var store = activeStores.remove(id); restoredStores.remove(id);
                if (store != null) store.deleteMessages(id);
            }
        };
        var functionTools = clientTools.stream().flatMap(tool -> tool.buildTools(metricsRecorder).stream()).toList();
        var skillCatalog = new SkillCatalog(new SkillFactory(new FileStorageProperties(storage == null ? testStorage() : storage)));
        var mcpTools =
                mcp == null ? noMcpTools() : mcp;
        var knowledgeTools = knowledge == null ? disabledKnowledgeTools() : knowledge.tools();
        return new LlmClientFactory(
                functionTools,
                chatMemoryStoreFactory,
                skillCatalog,
                new AiServiceListenerFactory(chatHistory, metricsRecorder),
                mcpTools,
                knowledgeTools,
                new ConversationRag(retrievers, knowledge == null ? Integer.MAX_VALUE : knowledge.budget(), chatHistory, null),
                new ToolErrorHandler(), sessionDeliverables, taskDeliverables,
                null, null);
    }
}
