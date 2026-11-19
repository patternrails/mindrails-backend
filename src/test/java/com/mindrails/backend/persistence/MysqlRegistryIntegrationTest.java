package com.mindrails.backend.persistence;

import com.mindrails.backend.asset.service.SessionDeliverables;

import com.mindrails.backend.agent.service.internal.TestSessionChat;

import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.history.config.HistoryConfiguration;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.*;
import com.mindrails.backend.provider.*;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Real MySQL and fresh Spring contexts; every model is replaced with a test double before invocation. */
@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class MysqlRegistryIntegrationTest {
    private final HikariDataSource source = new HikariDataSource();
    private final JdbcTemplate jdbc;
    private final UUID fixtureProvider = UUID.randomUUID();
    private final UUID streamingProvider = UUID.randomUUID();
    private final List<UUID> ownedAgents = new ArrayList<>();
    private final List<UUID> ownedMemories = new ArrayList<>();
    private final List<String> guardTables = new ArrayList<>();

    MysqlRegistryIntegrationTest() {
        source.setJdbcUrl(System.getenv().getOrDefault("MINDRAILS_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/mindrails"));
        source.setUsername(System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME", "mindrails_app"));
        source.setPassword(System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        source.addDataSourceProperty("connectionTimeZone", "UTC");
        source.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        jdbc = new JdbcTemplate(source);
    }

    @BeforeEach
    void insertTestProviders() {
        jdbc.update("""
                INSERT INTO providers (id,name,integration_mode,streaming,base_url,model_name,sort_order)
                VALUES (?,?, 'OpenAi',FALSE,'https://example.test/v1','fixture-chat',100),
                       (?,?, 'OpenAi',TRUE,'https://example.test/v1','fixture-chat',101)
                """, fixtureProvider.toString(), "fixture-" + fixtureProvider,
                streamingProvider.toString(), "fixture-" + streamingProvider);
    }

    ApplicationContextRunner runner() {
        return new ApplicationContextRunner().withUserConfiguration(ChatHistory.class, HistoryConfiguration.class, PersistenceConfiguration.class,
                        ProvidersConfiguration.class, AgentRegistryConfiguration.class, SessionRegistryConfiguration.class,
                        DefaultAgentService.class, DefaultSessionService.class, DefaultLoopSessionStreamService.class,
                        com.mindrails.backend.agent.service.internal.SessionForkService.class,
                        SessionDeliverables.class, LlmClientFactory.class)
                .withBean(com.mindrails.backend.mcp.api.toolspec.McpTools.class,
                        com.mindrails.backend.agent.service.internal.llm.TestLlmClients::noMcpTools)
                .withBean(com.mindrails.backend.knowledge.tool.KnowledgeTools.class,
                        com.mindrails.backend.agent.service.internal.llm.TestLlmClients::disabledKnowledgeTools)
                .withBean("agentMemoryStreamExecutor", java.util.concurrent.Executor.class, () -> Runnable::run)
                .withBean(MetricsRecorder.class, TestMetrics::disabled)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withBean("tools", List.class, List::of)
                .withPropertyValues("mindrails.mysql.url=" + source.getJdbcUrl(), "mindrails.mysql.username=" + source.getUsername(),
                        "mindrails.mysql.password=" + source.getPassword(), "OPENAI_API_KEY=test-openai", "DEEPSEEK_API_KEY=test-deepseek");
    }

    @AfterEach
    void cleanupOnlyOwnedData() {
        try {
            for (UUID memory : ownedMemories) jdbc.update("DELETE FROM chat_memory WHERE memory_id = ?", memory.toString());
            for (String table : guardTables) jdbc.execute("DROP TABLE IF EXISTS " + table);
            for (UUID id : ownedAgents) {
                jdbc.update(
                        "DELETE m FROM chat_memory m JOIN sessions s ON m.memory_id = s.memory_id"
                            + " WHERE s.parent_agent_id = ?", id.toString());
                jdbc.update("DELETE FROM sessions WHERE parent_agent_id = ?", id.toString());
                jdbc.update("DELETE FROM agents WHERE id = ?", id.toString());
            }
            jdbc.update("DELETE FROM providers WHERE id IN (?,?)", fixtureProvider.toString(), streamingProvider.toString());
        } finally { source.close(); }
    }

    UUID create(ApplicationContext context, String name) {
        UUID id = success(context.getBean(DefaultAgentService.class).create(Agent.builder().name(name).build()));
        ownedAgents.add(id);
        return id;
    }

    @Test
    void discoveryUsesOwnerScopedLiteralTitleSearchAndPagination() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(DefaultAgentService.class);
            var sessions = context.getBean(SessionRegistry.class);
            var provider = context.getBean(ProviderRegistry.class).find(fixtureProvider).orElseThrow();
            UUID firstAgent = create(context, "Discovery first"), secondAgent = create(context, "Discovery second");
            UUID current = success(service.startSession(firstAgent, provider.getId()));
            String needle = "Rust%_" + UUID.randomUUID();
            UUID one = success(service.startSession(firstAgent, provider.getId()));
            UUID two = success(service.startSession(secondAgent, provider.getId()));
            for (UUID id : List.of(current, one, two)) {
                var s = sessions.findManaged(id).orElseThrow();
                sessions.save(s.toBuilder().name(needle).build()).orElseThrow();
            }
            UUID owner = sessions.findManaged(current).orElseThrow().getOwnerId();
            var first = sessions.discover(owner, current, needle.toLowerCase(java.util.Locale.ROOT), 0, 1);
            var second = sessions.discover(owner, current, needle, 1, 1);
            assertEquals(1, first.size()); assertEquals(1, second.size());
            assertThat(List.of(first.getFirst().sessionId(), second.getFirst().sessionId())).containsExactlyInAnyOrder(one, two);
            assertTrue(sessions.discover(UUID.randomUUID(), current, "", 0, 10).isEmpty());
        });
    }

    @Test
    void retiredSmartAppRowsArePreservedAndExcludedFromAgentsAndSessions() {
        var firstAgent = new AtomicReference<UUID>();
        var legacyAgent = new AtomicReference<UUID>();
        var visibleSession = new AtomicReference<UUID>();
        var legacySession = new AtomicReference<UUID>();
        var legacyChild = new AtomicReference<UUID>();
        String title = "Registry retirement " + UUID.randomUUID();
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(DefaultAgentService.class);
            firstAgent.set(create(context, title + " ordinary"));
            legacyAgent.set(create(context, title + " historical"));
            visibleSession.set(success(service.startSession(firstAgent.get(), fixtureProvider)));
            legacySession.set(success(service.startSession(firstAgent.get(), fixtureProvider)));
            legacyChild.set(success(service.startSession(legacyAgent.get(), fixtureProvider)));
            jdbc.update("UPDATE agents SET special_key = ? WHERE id = ?", "runner:" + UUID.randomUUID(), legacyAgent.get().toString());
            jdbc.update("UPDATE sessions SET name = ? WHERE id IN (?, ?, ?)",
                    title, visibleSession.get().toString(), legacySession.get().toString(), legacyChild.get().toString());
            jdbc.update("UPDATE sessions SET smart_app = JSON_OBJECT('role', 'RUN'), provider_id = ? WHERE id = ?",
                    UUID.randomUUID().toString(), legacySession.get().toString());
        });
        runner().run(context -> {
            // A retired Session's missing Provider must not block startup.
            assertThat(context).hasNotFailed();
            var agents = context.getBean(AgentRegistry.class);
            var sessions = context.getBean(SessionRegistry.class);
            var service = context.getBean(DefaultAgentService.class);
            assertTrue(agents.findManaged(legacyAgent.get()).isEmpty());
            assertTrue(agents.listManaged(0, 100).stream().noneMatch(agent -> legacyAgent.get().equals(agent.getId())));
            assertTrue(agents.remove(legacyAgent.get()).isEmpty());
            assertTrue(sessions.findManaged(legacySession.get()).isEmpty());
            assertTrue(sessions.findManaged(legacyChild.get()).isEmpty());
            assertTrue(sessions.remove(legacySession.get()).isEmpty());
            assertEquals(List.of(visibleSession.get()), success(service.listSessions(firstAgent.get(), 0, 20))
                    .stream().map(Session::getId).toList());
            assertTrue(sessions.listManaged(legacyAgent.get(), 0, 20).isEmpty());
            var owner = sessions.findManaged(visibleSession.get()).orElseThrow().getOwnerId();
            assertEquals(List.of(visibleSession.get()), sessions.discover(owner, UUID.randomUUID(), title, 0, 20)
                    .stream().map(SessionRegistry.Candidate::sessionId).toList());
            assertEquals(2, jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id IN (?, ?)",
                    Integer.class, legacySession.get().toString(), legacyChild.get().toString()));
        });
    }

    @Test
    void databaseProviderCatalogMatchesPersistedIdsAndExposesManagedOperations() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var registry = context.getBean(ProviderRegistry.class);
            assertInstanceOf(MysqlProviderRegistry.class, registry);
            var ids = jdbc.queryForList("SELECT id FROM providers", String.class)
                    .stream().map(UUID::fromString).toList();
            assertThat(registry.list(0, 100).stream().map(Provider::getId).toList())
                    .containsExactlyInAnyOrderElementsOf(ids);
            assertEquals(jdbc.queryForObject("SELECT id FROM providers ORDER BY is_default DESC,sort_order,id LIMIT 1",
                    String.class), registry.defaultProvider().orElseThrow().getId().toString());
            assertThrows(UnsupportedOperationException.class, () -> registry.list(0, 100).clear());
            assertEquals(List.of("defaultProvider", "definition", "delete", "find", "list", "put"), java.util.Arrays.stream(ProviderRegistry.class.getDeclaredMethods())
                    .map(java.lang.reflect.Method::getName).sorted().toList());
        });
    }

    @Test
    void registryAndMemoryRecoverAcrossFreshApplicationContexts() {
        var agentId = new AtomicReference<UUID>();
        var sessionId = new AtomicReference<UUID>();
        var memoryId = new AtomicReference<UUID>();
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var model = new RecordingModel();
            var provider = context.getBean(ProviderRegistry.class).find(fixtureProvider).orElseThrow();
            provider.setChatModel(model);
            var service = context.getBean(DefaultAgentService.class);
            agentId.set(create(context, "Persistent agent"));
            success(service.update(agentId.get(), Agent.builder().name("Updated agent").description("persisted").systemMessage("Remember").build()));
            sessionId.set(success(service.startSession(agentId.get(), provider.getId())));
            var sessions = context.getBean(SessionRegistry.class);
            memoryId.set(sessions.findManaged(sessionId.get()).orElseThrow().getMemoryId());
            assertNotSame(sessions.findManaged(sessionId.get()).orElseThrow(), sessions.findManaged(sessionId.get()).orElseThrow());
            success(TestSessionChat.chat(context.getBean(DefaultLoopSessionStreamService.class), sessionId.get(), "First turn"));
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory WHERE memory_id = ?", Integer.class, memoryId.get().toString()));
        });
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(DefaultAgentService.class);
            Agent restored = success(service.get(agentId.get()));
            assertEquals("Updated agent", restored.getName());
            assertEquals("persisted", restored.getDescription());
            assertEquals(List.of(sessionId.get()), restored.getSessions().stream().map(Session::getId).toList());
            assertEquals(memoryId.get(), restored.getSessions().getFirst().getMemoryId());
            var model = new RecordingModel();
            context.getBean(ProviderRegistry.class).find(fixtureProvider).orElseThrow().setChatModel(model);
            success(TestSessionChat.chat(context.getBean(DefaultLoopSessionStreamService.class), sessionId.get(), "After restart"));
            assertEquals(4, model.requests.getFirst().messages().size());
            success(service.updateSession(agentId.get(), sessionId.get(), Session.builder().systemMessage("Changed").build(),
                    restored.getSessions().getFirst().getProvider().getId()));
            success(TestSessionChat.chat(context.getBean(DefaultLoopSessionStreamService.class), sessionId.get(), "After update"));
            assertEquals(6, model.requests.getLast().messages().size());
            UUID fork = success(service.forkSession(agentId.get(), sessionId.get()));
            assertEquals(2, success(service.listSessions(agentId.get(), 0, 20)).size());
            Session child = context.getBean(SessionRegistry.class).findManaged(fork).orElseThrow();
            assertEquals(agentId.get(), child.getParentAgentId());
            assertEquals(context.getBean(SessionRegistry.class).findManaged(sessionId.get()).orElseThrow().getName() + " (fork)", child.getName());
            assertNotEquals(memoryId.get(), child.getMemoryId());
            var copied = new com.mindrails.backend.memory.MysqlChatMemoryStore(context.getBean("memoryJdbcTemplate", org.springframework.jdbc.core.JdbcTemplate.class));
            assertEquals(copied.getMessages(memoryId.get()), copied.getMessages(child.getMemoryId()));
            ownedMemories.add(child.getMemoryId());
            success(service.deleteSession(agentId.get(), fork));
            ownedMemories.add(memoryId.get());
            success(service.delete(agentId.get()));
            assertEquals(0, jdbc.queryForObject(
                                            "SELECT COUNT(*) FROM sessions WHERE parent_agent_id ="
                                                + " ?", Integer.class, agentId.get().toString()));
            assertFalse(new com.mindrails.backend.memory.MysqlChatMemoryStore(context.getBean("memoryJdbcTemplate", org.springframework.jdbc.core.JdbcTemplate.class)).getMessages(memoryId.get()).isEmpty());
        });
    }

    @Test
    void deletesSessionsIndividuallyAndCanRetryAfterALaterDeleteFails() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(DefaultAgentService.class);
            var provider = context.getBean(ProviderRegistry.class).find(fixtureProvider).orElseThrow();
            provider.setChatModel(new RecordingModel());
            UUID owner = create(context, "Rollback verification");
            UUID first = success(service.startSession(owner, provider.getId()));
            UUID second = success(service.startSession(owner, provider.getId()));
            success(TestSessionChat.chat(context.getBean(DefaultLoopSessionStreamService.class), first, "First"));
            success(TestSessionChat.chat(context.getBean(DefaultLoopSessionStreamService.class), second, "Second"));
            var sessions = success(service.listSessions(owner, 0, 100));
            sessions.forEach(session -> ownedMemories.add(session.getMemoryId()));
            var store = new com.mindrails.backend.memory.MysqlChatMemoryStore(jdbc);
            List<List<ChatMessage>> memories = sessions.stream().map(s -> List.copyOf(store.getMessages(s.getMemoryId()))).toList();
            String table = "registry_delete_guard_" + UUID.randomUUID().toString().replace("-", "");
            guardTables.add(table);
            jdbc.execute("CREATE TABLE " + table + " (session_id CHAR(36) CHARACTER SET ascii COLLATE"
                    + " ascii_bin NOT NULL, FOREIGN KEY (session_id)"
                                            + " REFERENCES sessions(id))");
            jdbc.update("INSERT INTO " + table + " VALUES (?)", sessions.getLast().getId().toString());
            assertThrows(DataIntegrityViolationException.class, () -> service.delete(owner));
            assertEquals(List.of(sessions.getLast().getId()), success(service.listSessions(owner, 0, 100)).stream().map(Session::getId).toList());
            assertEquals("Rollback verification", success(service.get(owner)).getName());
            for (int i = 0; i < sessions.size(); i++) assertEquals(memories.get(i), store.getMessages(sessions.get(i).getMemoryId()));
            jdbc.execute("DROP TABLE " + table);
            success(service.delete(owner));
            for (int i = 0; i < sessions.size(); i++) assertEquals(memories.get(i), store.getMessages(sessions.get(i).getMemoryId()));
        });
    }

    @Test
    void agentCascadeCanDeleteSessionsDuringAnActiveChat() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var service = context.getBean(DefaultAgentService.class);
            var provider = context.getBean(ProviderRegistry.class).find(fixtureProvider).orElseThrow();
            CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
            provider.setChatModel(new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    entered.countDown();
                    try { assertTrue(release.await(10, TimeUnit.SECONDS)); }
                    catch (InterruptedException failure) { throw new RuntimeException(failure); }
                    return reply();
                }
            });
            UUID owner = create(context, "Active cascade");
            UUID active = success(service.startSession(owner, provider.getId()));
            ownedMemories.add(context.getBean(SessionRegistry.class).findManaged(active).orElseThrow().getMemoryId());
            success(service.startSession(owner, provider.getId()));
            try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
                var chat = executor.submit(() -> TestSessionChat.chat(context.getBean(DefaultLoopSessionStreamService.class), active, "Blocked"));
                try {
                    assertTrue(entered.await(5, TimeUnit.SECONDS));
                    success(service.delete(owner));
                    assertTrue(context.getBean(SessionRegistry.class).listAllManaged(owner).isEmpty());
                } finally { release.countDown(); }
                success(chat.get(5, TimeUnit.SECONDS));
            }
            assertEquals(ServiceResult.ErrorCode.NOT_FOUND, failure(service.get(owner)));
        });
    }

    @Test
    void streamingWithDetachedSessionSnapshotsCanBeCancelledAfterDeletion() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            var pending = new AtomicReference<StreamingChatResponseHandler>();
            var provider = context.getBean(ProviderRegistry.class).find(streamingProvider).orElseThrow();
            provider.setStreamingChatModel(new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { pending.set(handler); }
            });
            var service = context.getBean(DefaultAgentService.class);
            UUID owner = create(context, "Streaming snapshots");
            UUID id = success(service.startSession(owner, provider.getId()));
            ownedMemories.add(context.getBean(SessionRegistry.class).findManaged(id).orElseThrow().getMemoryId());
            var streams = context.getBean(DefaultLoopSessionStreamService.class);
            success(TestSessionChat.start(streams, id, new ClientCommand.Chat(UUID.randomUUID(), "Stream"), event -> {}));
            TestSessionChat.await(() -> pending.get() != null);
            success(service.delete(owner));
            success(streams.stop(id));
            pending.get().onCompleteResponse(reply());
            assertTrue(context.getBean(SessionRegistry.class).findManaged(id).isEmpty());
        });
    }

    @Test
    void fieldsPaginationVersionsAndRegistryMutationRollbackArePersisted() {
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            assertSame(context.getBean("memoryDataSource"), context.getBean("persistenceDataSource"));
            assertSame(context.getBean("memoryJdbcTemplate"), context.getBean("persistenceJdbcTemplate"));
            var service = context.getBean(DefaultAgentService.class);
            UUID owner = UUID.randomUUID(), skill = UUID.randomUUID(), connection = UUID.randomUUID(), knowledge = UUID.randomUUID();
            UUID id = success(service.create(Agent.builder().ownerId(owner).name("Full fields").description("中文")
                    .goal("Goal").systemMessage("Instruction").skillIds(List.of(skill)).connectionIds(List.of(connection))
                    .knowledgeBaseIds(java.util.List.of(knowledge)).build()));
            ownedAgents.add(id);
            create(context, "Another record");
            var registry = context.getBean(AgentRegistry.class);
            Agent stored = registry.findManaged(id).orElseThrow();
            assertEquals(owner, stored.getOwnerId());
            assertEquals(List.of(skill), stored.getSkillIds());
            assertEquals(List.of(connection), stored.getConnectionIds());
            assertEquals(java.util.List.of(knowledge), stored.getKnowledgeBaseIds());
            assertEquals("中文", stored.getDescription());
            assertTrue(stored.getCreatedAt().isBefore(java.time.Instant.now()));
            assertThrows(IllegalStateException.class, () -> registry.mutate(id, agent -> {
                agent.setName("Do not commit");
                throw new IllegalStateException("Reject update");
            }));
            assertEquals("Full fields", registry.findManaged(id).orElseThrow().getName());
            success(service.update(id, Agent.builder().name("Persisted update").build()));
            assertEquals(1, registry.findManaged(id).orElseThrow().getVersion());
            assertEquals(registry.listManaged(0, 100).getFirst().getId(), registry.listManaged(0, 1).getFirst().getId());
            var provider = context.getBean(ProviderRegistry.class).find(fixtureProvider).orElseThrow();
            UUID first = success(service.startSession(id, provider.getId()));
            UUID second = success(service.startSession(id, provider.getId()));
            var sessions = context.getBean(SessionRegistry.class);
            assertEquals(first, sessions.listManaged(id, 0, 1).getFirst().getId());
            assertEquals(second, sessions.listManaged(id, 1, 1).getFirst().getId());
            assertFalse(sessions.insertIfAbsent(sessions.findManaged(first).orElseThrow()));
            assertThrows(IllegalArgumentException.class, () -> sessions.save(sessions.findManaged(first).orElseThrow().toBuilder()
                    .parentAgentId(UUID.randomUUID()).build()));
            assertEquals(id, sessions.findManaged(first).orElseThrow().getParentAgentId());
            var accepted = sessions.findManaged(first).orElseThrow();
            var stale = accepted.clone();
            accepted.setSystemMessage("accepted configuration");
            sessions.save(accepted).orElseThrow();
            stale.setSystemMessage("stale configuration");
            assertThrows(org.springframework.dao.OptimisticLockingFailureException.class, () -> sessions.save(stale));
            assertEquals("accepted configuration", sessions.findManaged(first).orElseThrow().getSystemMessage());
            assertEquals(1, sessions.findManaged(first).orElseThrow().getVersion());
            assertTrue(context.getBean(ReadOnlyAgentRegistry.class).find(id).orElseThrow().sessionIds().containsAll(List.of(first, second)));
            String encoded = context.getBean(ObjectMapper.class).writeValueAsString(success(service.get(id)));
            assertFalse(encoded.contains("\"version\""));
            assertFalse(encoded.contains("\"llmClient\""));
            success(service.delete(id));
        });
    }

    @Test
    void missingProviderReferencesFailStartupWithoutDiscardingTheSession() {
        var owner = new AtomicReference<UUID>();
        var session = new AtomicReference<UUID>();
        var provider = new AtomicReference<UUID>();
        runner().run(context -> {
            assertThat(context).hasNotFailed();
            owner.set(create(context, "Missing provider verification"));
            provider.set(context.getBean(ProviderRegistry.class).find(fixtureProvider).orElseThrow().getId());
            session.set(success(context.getBean(DefaultAgentService.class).startSession(owner.get(), provider.get())));
        });
        jdbc.update("UPDATE sessions SET provider_id = ? WHERE id = ?", UUID.randomUUID().toString(), session.get().toString());
        try {
            runner().run(context -> assertThat(context).hasFailed());
            assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE id = ?", Integer.class, session.get().toString()));
        } finally { jdbc.update("UPDATE sessions SET provider_id = ? WHERE id = ?", provider.get().toString(), session.get().toString()); }
    }

    static ChatResponse reply() { return ChatResponse.builder().aiMessage(AiMessage.from("[\"reply\"]")).build(); }
    static class RecordingModel implements ChatModel {
        final List<ChatRequest> requests = new ArrayList<>();
        @Override public ChatResponse doChat(ChatRequest request) { requests.add(request); return reply(); }
    }
    static <T> T success(ServiceResult<T> result) {
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<T>) result).value();
    }
    static ServiceResult.ErrorCode failure(ServiceResult<?> result) { return assertInstanceOf(ServiceResult.Failure.class, result).code(); }
}
