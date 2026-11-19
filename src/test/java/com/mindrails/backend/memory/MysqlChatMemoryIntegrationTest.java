package com.mindrails.backend.memory;

import com.mindrails.backend.persistence.PersistenceConfiguration;

import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;

import static com.mindrails.backend.memory.SessionMemoryLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/** Explicitly run with ./gradlew mysqlTest against the local database; never uses a real LLM. */
class MysqlChatMemoryIntegrationTest {
    private final HikariDataSource dataSource = new HikariDataSource();
    private final UUID memoryId = UUID.randomUUID();
    private final UUID otherId = UUID.randomUUID();
    private final JdbcTemplate jdbc;
    private final MysqlChatMemoryStore store;

    MysqlChatMemoryIntegrationTest() {
        dataSource.setJdbcUrl(System.getenv().getOrDefault("MINDRAILS_MYSQL_URL", "jdbc:mysql://127.0.0.1:3306/mindrails"));
        dataSource.setUsername(System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME", "mindrails_app"));
        dataSource.setPassword(System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        jdbc = new JdbcTemplate(dataSource);
        store = new MysqlChatMemoryStore(jdbc);
    }

    @AfterEach
    void cleanUp() {
        try {
            store.deleteMessages(memoryId);
            store.deleteMessages(otherId);
        } finally {
            dataSource.close();
        }
    }

    @Test
    void roundTripsAllMessageTypesReplacesSnapshotsAndDeletesOnlyItsOwnMemory() {
        assertTrue(store.getMessages(memoryId).isEmpty());
        var call = ToolExecutionRequest.builder().id("tool-1").name("sample").arguments("{\"text\":\"中文\"}").build();
        List<ChatMessage> messages = List.of(SystemMessage.from("Instructions"), UserMessage.from("你好"),
                AiMessage.from(call), ToolExecutionResultMessage.from(call, "完成"), AiMessage.from("Done"));
        store.updateMessages(memoryId, messages);
        assertEquals(messages, new MysqlChatMemoryStore(new JdbcTemplate(dataSource)).getMessages(memoryId));
        store.updateMessages(otherId, List.of(UserMessage.from("Other")));
        store.updateMessages(memoryId, List.of(UserMessage.from("Replacement")));
        assertEquals(List.of(UserMessage.from("Replacement")), store.getMessages(memoryId));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM chat_memory WHERE memory_id = ?", Integer.class, memoryId.toString()));
        store.updateMessages(memoryId, List.of());
        assertTrue(store.getMessages(memoryId).isEmpty());
        store.deleteMessages(memoryId);
        store.deleteMessages(memoryId);
        assertEquals(List.of(UserMessage.from("Other")), store.getMessages(otherId));
    }

    @Test
    void newClientAndNewStoreRecoverContextAndReplaceSystemInstructions() {
        var firstModel = new RecordingModel();
        var session = Session.builder().id(UUID.randomUUID()).memoryId(memoryId).provider(provider(firstModel))
                .systemMessage("Original {{literal}}").build();
        var firstFactory = TestLlmClients.create(List.of(), () -> new MysqlChatMemoryStore(jdbc), new ChatMemoryProperties());
        TestLlmClients.chat(success(firstFactory.createClientForLoop(session)), memoryId, "Remember me");

        var secondModel = new RecordingModel();
        var rebuilt = session.toBuilder().provider(provider(secondModel)).systemMessage("Updated").build();
        var secondFactory = TestLlmClients.create(List.of(), () -> new MysqlChatMemoryStore(new JdbcTemplate(dataSource)), new ChatMemoryProperties());
        TestLlmClients.chat(success(secondFactory.createClientForLoop(rebuilt)), memoryId, "Continue");
        assertEquals(4, secondModel.requests.getFirst().messages().size());
        assertEquals(SystemMessage.from("Updated"), secondModel.requests.getFirst().messages().getFirst());

        rebuilt.setSystemMessage("Latest");
        TestLlmClients.chat(success(secondFactory.createClientForLoop(rebuilt)), memoryId, "Latest instruction");
        assertEquals(6, secondModel.requests.getLast().messages().size());
        assertEquals(SystemMessage.from("Latest"), store.getMessages(memoryId).getFirst());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void configuredClientRecoversAnInterruptedPersistentToolRoundBeforeItsFirstModelRequest(boolean streaming) {
        var call = ToolExecutionRequest.builder().id("interrupted-edit").name("edit")
                .arguments("{\"path\":\"unfinished").build();
        store.updateMessages(memoryId, List.of(UserMessage.from("Edit"), AiMessage.from(call),
                UserMessage.from("Previously failed retry")));
        var requests = new ArrayList<ChatRequest>();
        var provider = com.mindrails.backend.provider.Provider.builder().streaming(streaming);
        if (streaming) {
            provider.streamingChatModel(new StreamingChatModel() {
                @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                    requests.add(request);
                    handler.onCompleteResponse(reply());
                }
            });
        } else {
            provider.chatModel(new dev.langchain4j.model.chat.ChatModel() {
                @Override public dev.langchain4j.model.chat.response.ChatResponse doChat(ChatRequest request) {
                    requests.add(request);
                    return reply();
                }
            });
        }
        var properties = new ChatMemoryProperties();
        var factory = TestLlmClients.configured(List.of(), properties, new JdbcTemplate(dataSource));
        var session = Session.builder().id(UUID.randomUUID()).memoryId(memoryId).provider(provider.build()).build();
        var client = success(factory.createClientForLoop(session));

        if (streaming) {
            client.chatWithStream(memoryId, "Continue").onError(error -> fail(error)).start();
        } else {
            TestLlmClients.chat(client, memoryId, "Continue");
        }

        assertEquals(1, requests.size());
        var messages = requests.getFirst().messages();
        var recovered = assertInstanceOf(ToolExecutionResultMessage.class, messages.get(2), messages.toString());
        assertEquals(call.id(), recovered.id());
        assertTrue(recovered.text().contains("TOOL_RESULT_MISSING"));
        assertEquals(UserMessage.from("Previously failed retry"), messages.get(3));
        assertTrue(assertInstanceOf(UserMessage.class, messages.getLast()).singleText().startsWith("Continue"));
        assertEquals(6, new MysqlChatMemoryStore(new JdbcTemplate(dataSource)).getMessages(memoryId).size());

        // A synchronous failure can retain the cached client. It must recover on the next invocation too.
        store.updateMessages(memoryId, List.of(UserMessage.from("Edit"), AiMessage.from(call),
                UserMessage.from("Previously failed retry")));
        if (streaming) {
            client.chatWithStream(memoryId, "Try again").onError(error -> fail(error)).start();
        } else {
            TestLlmClients.chat(client, memoryId, "Try again");
        }
        assertEquals(2, requests.size());
        assertEquals(recovered, requests.getLast().messages().get(2));
    }

    @Test
    void streamingUsesTheSamePersistentWindowAndMemoryIdsAreIsolated() {
        List<ChatRequest> requests = new ArrayList<>();
        StreamingChatModel model = new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) {
                requests.add(request);
                handler.onCompleteResponse(reply());
            }
        };
        var session = Session.builder().id(UUID.randomUUID()).memoryId(memoryId)
                .provider(com.mindrails.backend.provider.Provider.builder().streaming(true).streamingChatModel(model).build()).build();
        var factory = TestLlmClients.create(List.of(), () -> new MysqlChatMemoryStore(jdbc), new ChatMemoryProperties());
        success(factory.createClientForLoop(session)).chatWithStream(memoryId, "First").onError(error -> fail(error)).start();

        success(factory.createClientForLoop(session)).chatWithStream(memoryId, "Second").onError(error -> fail(error)).start();
        assertEquals(3, requests.getLast().messages().size());
        assertEquals(4, new MysqlChatMemoryStore(new JdbcTemplate(dataSource)).getMessages(memoryId).size());
        assertTrue(store.getMessages(otherId).isEmpty());
    }

    @Test
    void configuredMysqlFactoryCreatesDistinctStoresSharingPersistentData() {
        new ApplicationContextRunner().withUserConfiguration(PersistenceConfiguration.class)
                .withPropertyValues("mindrails.chat-memory.max-messages=4",
                        "mindrails.mysql.url=" + dataSource.getJdbcUrl(),
                        "mindrails.mysql.username=" + dataSource.getUsername(),
                        "mindrails.mysql.password=" + dataSource.getPassword())
                .run(context -> {
                    assertNull(context.getStartupFailure());
                    var clients = TestLlmClients.configured(List.of(), context.getBean(ChatMemoryProperties.class),
                            context.getBean("memoryJdbcTemplate", JdbcTemplate.class));
                    var model = new SessionMemoryLifecycleTest.RecordingModel();
                    var session = Session.builder().id(UUID.randomUUID()).memoryId(memoryId)
                            .provider(SessionMemoryLifecycleTest.provider(model)).build();
                    var first = success(clients.createClientForLoop(session));
                    TestLlmClients.chat(first, memoryId, "Configured client");

                    var recreated = success(clients.createClientForLoop(session));
                    assertNotSame(first, recreated);
                    TestLlmClients.chat(recreated, memoryId, "After recreation");
                    assertEquals(3, model.requests.getLast().messages().size());
                    assertTrue(context.getBeansOfType(ChatMemoryStore.class).isEmpty());
                    assertEquals(4, context.getBean(ChatMemoryProperties.class).getMaxMessages());
                });
    }

    @Test
    void connectionFailuresAreReportedInsteadOfReturningEmptyMemory() {
        try (var unreachable = new HikariDataSource()) {
            unreachable.setJdbcUrl("jdbc:mysql://127.0.0.1:1/mindrails?connectTimeout=1000");
            unreachable.setUsername("test");
            unreachable.setPassword("test");
            unreachable.setConnectionTimeout(1000);
            assertThrows(DataAccessException.class,
                    () -> new MysqlChatMemoryStore(new JdbcTemplate(unreachable)).getMessages(memoryId));
        }
    }
}
