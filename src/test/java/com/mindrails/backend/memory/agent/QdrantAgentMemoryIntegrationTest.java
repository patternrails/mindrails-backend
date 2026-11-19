package com.mindrails.backend.memory.agent;

import com.mindrails.backend.agent.service.internal.TestSessionChat;

import com.mindrails.backend.metrics.TestMetrics;

import com.mindrails.backend.history.TestHistory;

import static com.mindrails.backend.memory.agent.AgentMemoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.embedding.EmbeddingProvider;
import com.mindrails.backend.embedding.JsonHttpClient;
import com.mindrails.backend.embedding.QdrantProperties;
import com.mindrails.backend.common.ServiceResult;
import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

/** Real Qdrant protocol, deterministic embeddings; only random test-owned collections are deleted. */
@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class QdrantAgentMemoryIntegrationTest {
    private final JsonMapper mapper = JsonMapper.builder().build();
    private final QdrantProperties settings = new QdrantProperties();
    private String collection;
    private final LocalEmbedding embedding = new LocalEmbedding();
    private QdrantAgentMemoryStore store;

    @BeforeEach void open() {
        settings.setHost(System.getenv().getOrDefault("MINDRAILS_QDRANT_HOST", "127.0.0.1"));
        settings.setPort(Integer.parseInt(System.getenv().getOrDefault("MINDRAILS_QDRANT_PORT", "6333")));
        collection = "test_agent_memory_" + UUID.randomUUID().toString().replace("-", "");
        store = new QdrantAgentMemoryStore(settings, collection, embedding, mapper);
    }

    @AfterEach void cleanup() {
        if (store != null) store.close();
        try (var http = new JsonHttpClient(mapper, 5000)) {
            http.request("DELETE", URI.create("http://" + settings.getHost() + ":" + settings.getPort()
                    + "/collections/" + collection), null, Map.of()).requireSuccess();
        }
    }

    @Test void semanticRecallUsesAgentFilterPinnedBudgetAndSurvivesReopen() {
        var f = new Fixture(store);
        UUID owner = f.agent(), other = f.agent(), session = f.session(owner);
        var database = success(f.memory.create(owner, input("database", "项目数据库使用 MySQL", false, null)));
        var pinned = success(f.memory.create(owner, input("language", "回答使用中文", true, null)));
        success(f.memory.create(other, input("database", "Foreign secret database", false, null)));
        var recalled = success(f.memory.search(owner, "采用哪种关系型引擎？"));
        assertEquals(List.of(pinned, database), recalled);
        assertEquals(List.of(database), success(f.memory.searchForTool(owner, "采用哪种关系型引擎？")));
        success(TestSessionChat.chat(f.streams, session, "采用哪种关系型引擎？"));
        assertTrue(lastUser(f.model.requests.getLast()).contains(database.content()));
        assertFalse(lastUser(f.model.requests.getLast()).contains("Foreign secret"));
        var memoryId = f.sessionRegistry.findManaged(session).orElseThrow().getMemoryId();
        assertEquals("采用哪种关系型引擎？", f.chatStore.getMessages(memoryId).stream()
                .filter(dev.langchain4j.data.message.UserMessage.class::isInstance)
                .map(dev.langchain4j.data.message.UserMessage.class::cast).findFirst().orElseThrow().singleText());
        try (var reopened = new QdrantAgentMemoryStore(settings, collection, embedding, mapper)) {
            assertEquals(database, reopened.find(database.memoryId(), database.id()).orElseThrow());
        }
        f.properties.getRetrieval().setMaxResults(1);
        assertEquals(List.of(pinned), success(f.memory.search(owner, "采用哪种关系型引擎？")));
        assertTrue(success(f.memory.searchForTool(owner, "烹饪食谱")).isEmpty());
        success(f.agents.delete(owner));
        assertTrue(store.entries(database.memoryId()).isEmpty());
    }

    @Test void versionsImmediateUpdatesDeletesAndEmbeddingFailuresKeepAuthoritativeContent() {
        var f = new Fixture(store);
        UUID owner = f.agent();
        var entry = success(f.memory.create(owner, input("database", "Database is MySQL", false, null)));
        assertEquals(ServiceResult.ErrorCode.CONFLICT, failure(f.memory.create(owner, input("database", "duplicate", false, null))));
        assertFalse(store.update(new AgentMemoryEntry(entry.id(), entry.agentId(), entry.memoryId(), entry.key(), entry.type(),
                "stale", entry.tags(), false, 0, null, entry.createdAt(), entry.updatedAt(), 1), 2));
        embedding.fail = true;
        assertEquals(ServiceResult.ErrorCode.INTERNAL_ERROR, failure(f.memory.update(owner, entry.id(), input("database", "Changed", false, 0L))));
        assertEquals(entry, success(f.memory.get(owner, entry.id())));
        embedding.fail = false;
        var updated = success(f.memory.update(owner, entry.id(), input("database", "Database is PostgreSQL", false, 0L)));
        assertEquals(1, updated.version());
        assertEquals(List.of(updated), success(f.memory.searchForTool(owner, "关系型引擎")));
        assertEquals(ServiceResult.ErrorCode.CONFLICT, failure(f.memory.delete(owner, updated.id(), 0)));
        success(f.memory.delete(owner, updated.id(), 1));
        assertTrue(success(f.memory.searchForTool(owner, "关系型引擎")).isEmpty());
    }

    @Test void embeddingPreparationDoesNotHoldCollectionGuardAndLateWriterCannotOverwrite() throws Exception {
        var f = new Fixture(store);
        UUID owner = f.agent();
        var entry = success(f.memory.create(owner, input("database", "Database is MySQL", false, null)));
        embedding.block = true;
        try (var executor = Executors.newFixedThreadPool(2)) {
            var delayed = executor.submit(() -> f.memory.update(owner, entry.id(), input("database", "blocked database", false, 0L)));
            assertTrue(embedding.entered.await(3, TimeUnit.SECONDS));
            assertEquals(entry, executor.submit(() -> success(f.memory.get(owner, entry.id()))).get(2, TimeUnit.SECONDS));
            var latest = success(f.memory.update(owner, entry.id(), input("database", "Latest database", false, 0L)));
            embedding.release.countDown();
            assertEquals(ServiceResult.ErrorCode.CONFLICT, failure(delayed.get(3, TimeUnit.SECONDS)));
            assertEquals(latest, success(f.memory.get(owner, entry.id())));
        } finally { embedding.release.countDown(); }
    }

    @Test void deletionRollbackRestoresPayloadAndVectorsWithoutEmbeddingAndProfileChangesAreRejected() {
        var f = new Fixture(store);
        UUID owner = f.agent();
        var entry = success(f.memory.create(owner, input("database", "Database", false, null)));
        var agent = f.agentRegistry.findManaged(owner).orElseThrow();
        embedding.fail = true;
        var result = f.memory.deleteWithAgent(agent, () -> ServiceResult.failure(ServiceResult.ErrorCode.CONFLICT, "injected"));
        assertEquals(ServiceResult.ErrorCode.CONFLICT, failure(result));
        assertEquals(entry, success(f.memory.get(owner, entry.id())));
        var changed = new EmbeddingProvider() {
            public List<Double> embed(String text) { return List.of(1.0, 0.0, 0.0); }
            public int dimensions() { return 3; }
            public String fingerprint() { return "different_model"; }
        };
        assertThrows(IllegalStateException.class, () -> new QdrantAgentMemoryStore(settings, collection, changed, mapper));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void modelToolsWriteSearchAndUpdateAndNextTurnAutomaticallyRecalls(boolean streaming) throws Exception {
        var f = new Fixture(store);
        var requests = new java.util.concurrent.CopyOnWriteArrayList<dev.langchain4j.model.chat.request.ChatRequest>();
        var step = new java.util.concurrent.atomic.AtomicInteger();
        java.util.function.Function<dev.langchain4j.model.chat.request.ChatRequest, dev.langchain4j.model.chat.response.ChatResponse> respond = request -> {
            requests.add(request);
            return switch (step.getAndIncrement()) {
                case 0 -> call("agent_memory_create", "{\"key\":\"database\",\"content\":\"Database is MySQL\"}");
                case 1 -> {
                    assertEquals("CREATED", lastTool(request).path("status").asText());
                    yield call("agent_memory_search", "{\"query\":\"关系型引擎\"}");
                }
                case 2 -> {
                    assertTrue(lastTool(request).toString().contains("Database is MySQL"));
                    yield call("agent_memory_update", "{\"key\":\"database\",\"expectedVersion\":0,\"type\":\"DECISION\",\"content\":\"Database is PostgreSQL\",\"tags\":[]}");
                }
                case 3 -> {
                    assertEquals("UPDATED", lastTool(request).path("status").asText());
                    yield reply();
                }
                default -> reply();
            };
        };
        f.provider.setStreaming(streaming);
        f.provider.setChatModel(new dev.langchain4j.model.chat.ChatModel() {
            @Override public dev.langchain4j.model.chat.response.ChatResponse doChat(dev.langchain4j.model.chat.request.ChatRequest request) { return respond.apply(request); }
        });
        f.provider.setStreamingChatModel(new dev.langchain4j.model.chat.StreamingChatModel() {
            @Override public void doChat(dev.langchain4j.model.chat.request.ChatRequest request,
                    dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) { handler.onCompleteResponse(respond.apply(request)); }
        });
        UUID owner = f.agent(), session = f.session(owner);
        for (String query : List.of("请记住数据库约定", "采用哪种关系型引擎？")) {
            if (!streaming) success(TestSessionChat.chat(f.streams, session, query));
            else {
                var stream = TestSessionChat.service(f.sessionRegistry, f.factory, TestHistory.disabled(), TestMetrics.disabled());
                var completed = new CountDownLatch(1);
                var events = new java.util.concurrent.CopyOnWriteArrayList<com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent>();
                success(TestSessionChat.start(stream, session, new com.mindrails.backend.agent.api.websocket.protocol.ClientCommand.Chat(UUID.randomUUID(), query), event -> {
                    events.add(event);
                    if (event instanceof com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent.Completed
                            || event instanceof com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent.Failed) completed.countDown();
                }));
                assertTrue(completed.await(5, TimeUnit.SECONDS));
                assertInstanceOf(com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent.Completed.class, events.getLast());
            }
        }
        assertEquals(5, requests.size());
        assertTrue(lastUser(requests.getLast()).contains("Database is PostgreSQL"));
        assertFalse(lastUser(requests.getLast()).contains("Database is MySQL"));
        assertEquals(1, success(f.memory.getByKey(owner, "database")).version());
    }

    @Test void rebuildCopiesOriginalEntriesToANewCollectionWithoutOldEmbeddingProvider() throws Exception {
        var f = new Fixture(store);
        UUID owner = f.agent();
        var entry = success(f.memory.create(owner, input("database", "Database is MySQL", true, null)));
        String targetCollection = "test_rebuild_" + UUID.randomUUID().toString().replace("-", "");
        var properties = new AgentMemoryProperties();

        properties.getQdrant().setCollection(targetCollection);
        var env = new org.springframework.mock.env.MockEnvironment()
                .withProperty("spring.main.web-application-type", "none")
                .withProperty("mindrails.agent-memory.migrate.source", "qdrant")
                .withProperty("mindrails.agent-memory.migrate.source-collection", collection);
        try (var target = new QdrantAgentMemoryStore(settings, targetCollection, embedding, mapper)) {
            var context = org.mockito.Mockito.mock(org.springframework.context.ConfigurableApplicationContext.class);
            var provider = org.mockito.Mockito.mock(org.springframework.beans.factory.ObjectProvider.class);
            var migration = new AgentMemoryMigration(target, properties, settings, provider, mapper, f.agentRegistry, env, context);
            migration.run(new org.springframework.boot.DefaultApplicationArguments());
            assertEquals(entry, target.find(entry.memoryId(), entry.id()).orElseThrow());
            migration.run(new org.springframework.boot.DefaultApplicationArguments()); // idempotent retry
            assertEquals(1, target.entries(entry.memoryId()).size());
            org.mockito.Mockito.verifyNoInteractions(provider); // no MySQL or old model required
            org.mockito.Mockito.verify(context, org.mockito.Mockito.times(2)).close();
        } finally {
            try (var http = new JsonHttpClient(mapper, 5000)) {
                http.request("DELETE", URI.create("http://" + settings.getHost() + ":" + settings.getPort()
                        + "/collections/" + targetCollection), null, Map.of()).requireSuccess();
            }
        }
    }

    @Test void springWiresQdrantStoreAndAgentMemoryTools() {
        var f = new Fixture();
        var registry = org.mockito.Mockito.mock(com.mindrails.backend.embedding.EmbeddingProviderRegistry.class);
        org.mockito.Mockito.when(registry.defaultProviderName()).thenReturn(java.util.Optional.empty());
        org.mockito.Mockito.when(registry.require("test-default")).thenReturn(embedding);
        new org.springframework.boot.test.context.runner.ApplicationContextRunner()
                .withUserConfiguration(AgentMemoryConfiguration.class)
                .withBean(com.mindrails.backend.agent.registry.AgentRegistry.class, () -> f.agentRegistry)
                .withBean(com.mindrails.backend.agent.registry.SessionRegistry.class, () -> f.sessionRegistry)
                .withBean(tools.jackson.databind.ObjectMapper.class, () -> mapper)
                .withBean(com.mindrails.backend.embedding.EmbeddingProviderRegistry.class, () -> registry)
                .withPropertyValues("mindrails.qdrant.host=" + settings.getHost(),
                        "mindrails.qdrant.port=" + settings.getPort(), "mindrails.agent-memory.qdrant.collection=" + collection)
                .run(context -> {
                    org.assertj.core.api.Assertions.assertThat(context).hasNotFailed()
                            .hasSingleBean(AgentMemoryRetriever.class)
                            .hasSingleBean(com.mindrails.backend.tool.spec.AgentMemoryTools.class);
                    assertInstanceOf(QdrantAgentMemoryStore.class, context.getBean(AgentMemoryStore.class));
                    var memory = context.getBean(AgentMemoryService.class);
                    assertFalse(memory.available());
                    org.mockito.Mockito.when(registry.defaultProviderName()).thenReturn(java.util.Optional.of("test-default"));
                    UUID owner = f.agent();
                    var entry = success(memory.create(owner, input("database", "Database is MySQL", false, null)));
                    assertEquals(List.of(entry), success(memory.searchForTool(owner, "关系型引擎")));
                });
    }

    static class LocalEmbedding implements EmbeddingProvider {
        volatile boolean fail, block;
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        public List<Double> embed(String text) {
            if (fail) throw new IllegalStateException("Injected embedding failure");
            if (block && text.contains("blocked")) {
                entered.countDown();
                try { assertTrue(release.await(5, TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted"); }
            }
            if (text.contains("language") || text.contains("中文")) return List.of(0.0, 0.0, 1.0);
            if (text.contains("烹饪")) return List.of(0.0, 1.0, 0.0);
            return List.of(1.0, 0.0, 0.0);
        }
        public int dimensions() { return 3; }
        public String fingerprint() { return "local_test_v1"; }
    }
}
