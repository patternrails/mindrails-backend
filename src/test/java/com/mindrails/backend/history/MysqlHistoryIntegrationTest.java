package com.mindrails.backend.history;

import com.mindrails.backend.asset.service.SessionDeliverables;

import com.mindrails.backend.agent.service.internal.TestSessionChat;

import com.mindrails.backend.metrics.TestMetrics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.DefaultAgentService;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.config.HistoryConfiguration;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.store.MysqlHistoryStore;
import com.mindrails.backend.persistence.PersistenceConfiguration;
import com.mindrails.backend.provider.ProviderRegistry;
import com.mindrails.backend.provider.ProvidersConfiguration;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

/** Real MySQL, local fake models; cleanup is limited to this test's random IDs. */
@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class MysqlHistoryIntegrationTest {
    private final HikariDataSource source = source();
    private final JdbcTemplate jdbc = new JdbcTemplate(source);
    private final List<UUID> historyIds = new ArrayList<>();
    private final List<UUID> agentIds = new ArrayList<>();
    private final List<UUID> sessionIds = new ArrayList<>();
    private final List<UUID> providerIds = new ArrayList<>();

    static HikariDataSource source() {
        var source = new HikariDataSource();
        source.setJdbcUrl(
                System.getenv()
                        .getOrDefault(
                                "MINDRAILS_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/mindrails"));
        source.setUsername(
                System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME", "mindrails_app"));
        source.setPassword(
                System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        source.addDataSourceProperty("connectionTimeZone", "UTC");
        source.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        return source;
    }

    @AfterEach
    void cleanupOnlyOwnedData() {
        try {
            for (UUID id : historyIds) {
                jdbc.update("DELETE FROM history WHERE id=?", id.toString());
            }
            for (UUID id : sessionIds) {
                jdbc.update("DELETE FROM history WHERE session_id=?", id.toString());
            }
            for (UUID id : agentIds) {
                jdbc.update(
                        "DELETE m FROM chat_memory m JOIN sessions s ON m.memory_id=s.memory_id"
                                + " WHERE s.parent_agent_id=?",
                        id.toString());
                jdbc.update("DELETE FROM sessions WHERE parent_agent_id=?", id.toString());
                jdbc.update("DELETE FROM agents WHERE id=?", id.toString());
            }
            for (UUID id : providerIds) jdbc.update("DELETE FROM providers WHERE id=?", id.toString());
        } finally {
            source.close();
        }
    }

    private MysqlHistoryStore store() {
        return new MysqlHistoryStore(jdbc);
    }

    private Integer count(UUID session) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM history WHERE session_id=?",
                Integer.class,
                session.toString());
    }

    private static <T> T success(ServiceResult<T> result) {
        return ((ServiceResult.Success<T>) assertInstanceOf(ServiceResult.Success.class, result))
                .value();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void syncAndStreamWriteJsonAndSurviveRecreatedConnections(boolean stream) {
        var captured = new AtomicReference<HistoryRecord>();
        var fixture =
                new HistoryChatTest.Fixture(
                        stream
                                ? null
                                : new ChatModel() {
                                    @Override
                                    public ChatResponse doChat(ChatRequest request) {
                                        return HistoryChatTest.reply("[\"数据库\"]");
                                    }
                                },
                        stream
                                ? new StreamingChatModel() {
                                    @Override
                                    public void doChat(
                                            ChatRequest request,
                                            StreamingChatResponseHandler handler) {
                                        handler.onCompleteResponse(HistoryChatTest.reply("数据库"));
                                    }
                                }
                                : null,
                        record -> {
                            captured.set(record);
                            historyIds.add(record.id());
                            store().insert(record);
                        },
                        List.of(),
                        3);
        success(TestSessionChat.chat(fixture.stream, fixture.session.getId(), "你好"));
        var record = captured.get();
        assertNotNull(record);
        assertEquals(1, count(fixture.session.getId()));
        try (var recreated = source()) {
            var recovered =
                    new JdbcTemplate(recreated)
                            .queryForMap(
                                    "SELECT * FROM history WHERE id=?", record.id().toString());
            assertEquals("COMPLETED", recovered.get("status"));
            assertEquals("STREAM", recovered.get("transport"));
            assertEquals(record.ownerId().toString(), recovered.get("owner_id"));
            assertEquals(
                    2,
                    JsonMapper.builder()
                            .build()
                            .readTree((String) recovered.get("messages"))
                            .size());
            assertNotNull(recovered.get("invocation_id"));
        }
    }

    @Test
    void configuredServicesPreserveArchivesAfterSessionAndAgentDeletion() {
        UUID fixture = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO providers (id,name,integration_mode,streaming,base_url,model_name,sort_order)
                VALUES (?,?,'OpenAi',FALSE,'https://example.test/v1','fixture-chat',100)
                """, fixture.toString(), "fixture-" + fixture);
        providerIds.add(fixture);
        new ApplicationContextRunner()
                .withUserConfiguration(
                        HistoryConfiguration.class,
                        PersistenceConfiguration.class,
                        ProvidersConfiguration.class,
                        AgentRegistryConfiguration.class,
                        SessionRegistryConfiguration.class,
                        DefaultAgentService.class,
                        DefaultSessionService.class,
                        DefaultLoopSessionStreamService.class,
                        ChatHistory.class,
                        SessionDeliverables.class, LlmClientFactory.class)
                .withBean(com.mindrails.backend.mcp.api.toolspec.McpTools.class,
                        com.mindrails.backend.agent.service.internal.llm.TestLlmClients::noMcpTools)
                .withBean(com.mindrails.backend.knowledge.tool.KnowledgeTools.class,
                        com.mindrails.backend.agent.service.internal.llm.TestLlmClients::disabledKnowledgeTools)
                .withBean("agentMemoryStreamExecutor", java.util.concurrent.Executor.class, () -> Runnable::run)
                .withBean(MetricsRecorder.class, TestMetrics::disabled)
                .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
                .withBean("tools", List.class, List::of)
                .withPropertyValues("mindrails.mysql.url=" + source.getJdbcUrl(),
                        "mindrails.mysql.username=" + source.getUsername(),
                        "mindrails.mysql.password=" + source.getPassword(),
                        "OPENAI_API_KEY=test",
                        "DEEPSEEK_API_KEY=test")
                .run(
                        context -> {
                            assertThat(context).hasNotFailed();
                            var services = context.getBean(DefaultAgentService.class);
                            var provider =
                                    context.getBean(ProviderRegistry.class)
                                            .find(fixture)
                                            .orElseThrow();
                            provider.setStreaming(false);
                            provider.setChatModel(
                                    new ChatModel() {
                                        @Override
                                        public ChatResponse doChat(ChatRequest request) {
                                            return ChatResponse.builder()
                                                    .aiMessage(AiMessage.from("[\"Done\"]"))
                                                    .build();
                                        }
                                    });
                            UUID owner = UUID.randomUUID();
                            UUID agent =
                                    success(
                                            services.create(
                                                    Agent.builder()
                                                            .name("History integration")
                                                            .ownerId(owner)
                                                            .build()));
                            agentIds.add(agent);
                            UUID first = success(services.startSession(agent, provider.getId()));
                            sessionIds.add(first);
                            success(TestSessionChat.chat(context.getBean(DefaultLoopSessionStreamService.class), first, "Archive this turn"));
                            success(services.deleteSession(agent, first));
                            assertEquals(1, count(first));
                            UUID second = success(services.startSession(agent, provider.getId()));
                            sessionIds.add(second);
                            success(TestSessionChat.chat(context.getBean(DefaultLoopSessionStreamService.class), second, "Keep after deleting Agent"));
                            success(services.delete(agent));
                            assertEquals(1, count(first));
                            assertEquals(1, count(second));
                            assertTrue(
                                    context.getBean(AgentRegistry.class)
                                            .findManaged(agent)
                                            .isEmpty());
                            assertTrue(
                                    context.getBean(SessionRegistry.class)
                                            .findManaged(second)
                                            .isEmpty());
                            assertEquals(
                                    owner.toString(),
                                    jdbc.queryForObject(
                                            "SELECT owner_id FROM history WHERE session_id=?",
                                            String.class,
                                            first.toString()));
                        });
        try (var reopened = source()) {
            assertEquals(
                    2,
                    new JdbcTemplate(reopened)
                            .queryForObject(
                                    "SELECT COUNT(*) FROM history WHERE agent_id=?",
                                    Integer.class,
                                    agentIds.getFirst().toString()));
        }
    }

    @Test
    void insertIsIdempotentOnlyByServerTurnIdAndPreservesFirstSnapshot() {
        UUID id = UUID.randomUUID();
        historyIds.add(id);
        UUID requestId = UUID.randomUUID();
        UUID session = UUID.randomUUID();
        var now = Instant.now();
        var first =
                new HistoryRecord(
                        id,
                        null,
                        UUID.randomUUID(),
                        session,
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        requestId,
                        UUID.randomUUID(),
                        HistoryRecord.Transport.STREAM,
                        HistoryRecord.Status.CANCELLED,
                        "[]",
                        "{}",
                        now,
                        now,
                        1);
        store().insert(first);
        store().insert(first);
        var changed =
                new HistoryRecord(
                        id,
                        first.ownerId(),
                        first.agentId(),
                        session,
                        first.memoryId(),
                        first.providerId(),
                        requestId,
                        first.invocationId(),
                        first.transport(),
                        HistoryRecord.Status.COMPLETED,
                        "[]",
                        "{}",
                        now,
                        now,
                        1);
        store().insert(changed);
        assertEquals(
                "CANCELLED",
                jdbc.queryForObject(
                        "SELECT status FROM history WHERE id=?", String.class, id.toString()));
        UUID another = UUID.randomUUID();
        historyIds.add(another);
        store().insert(
                        new HistoryRecord(
                                another,
                                null,
                                first.agentId(),
                                session,
                                first.memoryId(),
                                first.providerId(),
                                requestId,
                                UUID.randomUUID(),
                                first.transport(),
                                HistoryRecord.Status.COMPLETED,
                                "[]",
                                "{}",
                                now,
                                now,
                                1));
        assertEquals(2, count(session));
    }

    @Test
    void storageMergesIndependentEntriesAndKeepsCancellationUnderLateCallbacks() {
        var session = com.mindrails.backend.agent.model.Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).build();
        var parameters = com.mindrails.backend.agent.model.LlmClient.parameters(session, UUID.randomUUID(), "WEBSOCKET");
        UUID id = parameters.get(com.mindrails.backend.agent.model.LlmClient.RUN_ID);
        historyIds.add(id);
        var archive = store();
        archive.insert(com.mindrails.backend.history.service.internal.HistoryRecords.start(parameters, "Question"));
        archive.insert(com.mindrails.backend.history.service.internal.HistoryRecords.partial(parameters, "answer", "Partial"));
        var terminal = com.mindrails.backend.history.service.internal.HistoryRecords.finish(parameters, HistoryRecord.Status.CANCELLED, null, null, null, "RUN_CANCELLED", "Cancelled");
        archive.insert(terminal);
        archive.insert(terminal);
        archive.insert(com.mindrails.backend.history.service.internal.HistoryRecords.partial(parameters, "answer", "late"));
        var row = jdbc.queryForMap("SELECT status,messages,metadata FROM history WHERE id=?", id.toString());
        assertEquals("CANCELLED", row.get("status"));
        assertTrue(row.get("messages").toString().contains("Question"));
        assertEquals("Partial", JsonMapper.builder().build().readTree(row.get("metadata").toString()).path("partial").path("answer").asString());
    }

    @Test
    void invalidJsonIsNotSilentlyIgnoredAsADuplicate() {
        UUID id = UUID.randomUUID();
        historyIds.add(id);
        var now = Instant.now();
        assertThrows(
                RuntimeException.class,
                () ->
                        store().insert(
                                        new HistoryRecord(
                                                id,
                                                null,
                                                null,
                                                UUID.randomUUID(),
                                                UUID.randomUUID(),
                                                null,
                                                null,
                                                null,
                                                HistoryRecord.Transport.SYNC,
                                                HistoryRecord.Status.FAILED,
                                                "invalid JSON",
                                                "{}",
                                                now,
                                                now,
                                                1)));
    }
}
