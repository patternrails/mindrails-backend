package com.mindrails.backend.mcp;

import com.mindrails.backend.identity.IdentityService;
import com.mindrails.backend.agent.service.internal.TestSessionChat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService;
import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.memory.ChatMemoryProperties;
import com.mindrails.backend.metrics.InMemoryMetricsRepository;
import com.mindrails.backend.metrics.MetricsHealth;
import com.mindrails.backend.metrics.MetricsProperties;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.provider.Provider;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.output.FinishReason;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class McpChatTest {
    @ParameterizedTest
    @CsvSource({"false,false", "true,false", "false,true", "true,true"})
    void fullRestAndWebsocketLoopArchivesResultsAndRecordsMcpSeparately(
            boolean streaming, boolean toolFails) throws Exception {
        var archive = new CopyOnWriteArrayList<HistoryRecord>();
        var agents = new AgentRegistryConfiguration().agentRegistry();
        var history = TestHistory.create(TestHistory.collect(archive), agents);
        var props = new MetricsProperties();
        var repository =
                new InMemoryMetricsRepository(new MetricsProperties(), new MetricsHealth());
        try (var metrics = new TestMetrics(props, repository, new MetricsHealth(), false);
                var mcp = new McpRuntimeTest.Fixture(metrics.recorder(), history)) {
            var server = mcp.stdio("chat-mock", 30000);
            var requests = new CopyOnWriteArrayList<ChatRequest>();
            var sequence = new AtomicInteger();
            java.util.function.Function<ChatRequest, ChatResponse> respond =
                    request -> {
                        requests.add(request);
                        if (sequence.getAndIncrement() == 0) {
                            var tool =
                                    request.parameters().toolSpecifications().stream()
                                            .filter(
                                                    t ->
                                                            t.description()
                                                                    .contains(
                                                                            toolFails
                                                                                    ? "tool fail."
                                                                                    : "tool add."))
                                            .findFirst()
                                            .orElseThrow();
                            return ChatResponse.builder()
                                    .aiMessage(
                                            AiMessage.from(
                                                    ToolExecutionRequest.builder()
                                                            .id("addition")
                                                            .name(tool.name())
                                                            .arguments(
                                                                    toolFails
                                                                            ? "{}"
                                                                            : "{\"a\":4,\"b\":7}")
                                                            .build()))
                                    .finishReason(FinishReason.TOOL_EXECUTION)
                                    .build();
                        }
                        assertTrue(
                                request.messages().stream()
                                        .anyMatch(
                                                message ->
                                                        message
                                                                        instanceof
                                                                        ToolExecutionResultMessage
                                                                                result
                                                                && (toolFails
                                                                        ? result.text()
                                                                                .contains(
                                                                                        "Deliberate"
                                                                                            + " mock"
                                                                                            + " failure")
                                                                        : result.text()
                                                                                .trim()
                                                                                .equals("11"))));
                        return ChatResponse.builder()
                                .aiMessage(AiMessage.from(streaming ? "11" : "[\"11\"]"))
                                .finishReason(FinishReason.STOP)
                                .build();
                    };
            var model =
                    new ChatModel() {
                        @Override
                        public ChatResponse doChat(ChatRequest request) {
                            return respond.apply(request);
                        }
                    };
            var streamModel =
                    new StreamingChatModel() {
                        @Override
                        public void doChat(
                                ChatRequest request, StreamingChatResponseHandler handler) {
                            handler.onCompleteResponse(respond.apply(request));
                        }
                    };
            var memory = new InMemoryChatMemoryStore();
            var factory =
                    TestLlmClients.create(
                            List.of(),
                            () -> memory,
                            new ChatMemoryProperties(),
                            null,
                            mcp.tools,
                            null,
                            null,
                            history,
                            metrics.recorder());
            var sessions = new SessionRegistryConfiguration().sessionRegistry();
            var agent = Agent.builder().id(UUID.randomUUID()).name("MCP test").build();
            agents.insertIfAbsent(agent);
            var session =
                    Session.builder()
                            .id(UUID.randomUUID())
                            .parentAgentId(agent.getId())
                            .memoryId(UUID.randomUUID())
                            .provider(
                                    Provider.builder()
                                            .id(UUID.randomUUID())
                                            .streaming(streaming)
                                            .chatModel(streaming ? null : model)
                                            .streamingChatModel(streaming ? streamModel : null)
                                            .build())
                            .build();
            sessions.insertIfAbsent(session);
            {
                if (streaming) {
                    var events = new CopyOnWriteArrayList<ChatStreamEvent>();
                    var terminal = new CountDownLatch(1);
                    var service =
                            TestSessionChat.service(
                                    sessions, factory, history, metrics.recorder());
                    assertInstanceOf(
                            ServiceResult.Success.class,
                            TestSessionChat.start(service,
                                    session.getId(),
                                    new ClientCommand.Chat(UUID.randomUUID(), "Add 4 and 7"),
                                    event -> {
                                        events.add(event);
                                        if (event instanceof ChatStreamEvent.Completed
                                                || event instanceof ChatStreamEvent.Failed) {
                                            terminal.countDown();
                                        }
                                    }));
                    assertTrue(terminal.await(5, TimeUnit.SECONDS));
                    assertTrue(
                            events.stream().anyMatch(ChatStreamEvent.Completed.class::isInstance),
                            events.toString());
                    var start =
                            events.stream()
                                    .filter(ChatStreamEvent.ToolStarted.class::isInstance)
                                    .map(ChatStreamEvent.ToolStarted.class::cast)
                                    .findFirst()
                                    .orElseThrow();
                    assertEquals(server.id().toString(), start.source().get("serverId"));
                    assertTrue(
                            events.stream()
                                    .anyMatch(
                                            toolFails
                                                    ? ChatStreamEvent.ToolFailed.class::isInstance
                                                    : ChatStreamEvent.ToolCompleted.class
                                                            ::isInstance));
                } else {
                    var service =
                            TestSessionChat.service(sessions, factory, history, metrics.recorder());
                    var result = TestSessionChat.chat(service, session.getId(), "Add 4 and 7");
                    assertInstanceOf(ServiceResult.Success.class, result);
                    assertEquals("[\"11\"]", ((ServiceResult.Success<ChatStreamEvent.Completed>) result).value().answer());
                }
                metrics.flush();
                var saved = repository.snapshots().getFirst();
                assertEquals(1, saved.mcpToolCallCount());
                assertEquals(0, saved.functionCallCount());
                assertEquals("SUCCEEDED", saved.status());
                assertEquals(server.id().toString(), saved.capabilityCalls().getFirst().serverId());
                assertEquals(toolFails ? 1 : 0, saved.capabilityFailedCount());
            }
            assertEquals(2, requests.size());
            assertEquals(1, archive.size());
            assertTrue(archive.getFirst().messages().contains("11"));
            assertTrue(archive.getFirst().metadata().contains("originalToolName"));
            assertTrue(archive.getFirst().metadata().contains("chat-mock"));
        }
    }

    @Test
    void skillsAndMcpRemainAvailableInTheSameTurn(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        var skill = java.nio.file.Files.createDirectories(root.resolve("skill").resolve(IdentityService.ADMIN_ID.toString()).resolve("calculate"));
        java.nio.file.Files.writeString(
                skill.resolve("SKILL.md"),
                "---\n"
                        + "name: calculate\n"
                        + "description: Use for addition tasks\n"
                        + "---\n"
                        + "Use the MCP add tool to add numbers.");
        try (var mcp = new McpRuntimeTest.Fixture()) {
            mcp.stdio("skill-mcp", 30000);
            var step = new AtomicInteger();
            var model =
                    new ChatModel() {
                        @Override
                        public ChatResponse doChat(ChatRequest request) {
                            var tools = request.parameters().toolSpecifications();
                            assertTrue(
                                    tools.stream()
                                            .anyMatch(t -> t.name().equals("activate_skill")));
                            var add =
                                    tools.stream()
                                            .filter(t -> t.description().contains("tool add."))
                                            .findFirst()
                                            .orElseThrow();
                            if (step.getAndIncrement() == 0) {
                                return ChatResponse.builder()
                                        .aiMessage(
                                                AiMessage.from(
                                                        ToolExecutionRequest.builder()
                                                                .id("skill")
                                                                .name("activate_skill")
                                                                .arguments(
                                                                        "{\"skill_name\":\"calculate\"}")
                                                                .build()))
                                        .build();
                            }
                            assertTrue(
                                    request.messages().toString().contains("Use the MCP add tool"));
                            if (step.get() == 2) {
                                return ChatResponse.builder()
                                        .aiMessage(
                                                AiMessage.from(
                                                        ToolExecutionRequest.builder()
                                                                .id("add")
                                                                .name(add.name())
                                                                .arguments("{\"a\":4,\"b\":7}")
                                                                .build()))
                                        .build();
                            }
                            assertTrue(
                                    request.messages().stream()
                                            .anyMatch(
                                                    m ->
                                                            m
                                                                            instanceof
                                                                            ToolExecutionResultMessage
                                                                                    t
                                                                    && t.text()
                                                                            .trim()
                                                                            .equals("11")));
                            return ChatResponse.builder()
                                    .aiMessage(AiMessage.from("[\"11\"]"))
                                    .build();
                        }
                    };
            var factory =
                    TestLlmClients.create(
                            List.of(),
                            InMemoryChatMemoryStore::new,
                            new ChatMemoryProperties(),
                            null,
                            mcp.tools,
                            root);
            var registry = new SessionRegistryConfiguration().sessionRegistry();
            var session =
                    Session.builder()
                            .id(UUID.randomUUID())
                            .parentAgentId(UUID.randomUUID())
                            .memoryId(UUID.randomUUID())
                            .provider(
                                    Provider.builder()
                                            .id(UUID.randomUUID())
                                            .streaming(false)
                                            .chatModel(model)
                                            .build())
                            .build();
            registry.insertIfAbsent(session);
            var result = TestSessionChat.chat(TestSessionChat.service(registry, factory, TestHistory.disabled(), TestMetrics.disabled()),
                    session.getId(), "Use calculate");
            assertEquals("[\"11\"]", assertInstanceOf(ServiceResult.Success.class, result).value() instanceof ChatStreamEvent.Completed completed ? completed.answer() : null);
            assertEquals(3, step.get());
        }
    }

    @Test
    void existingSessionPicksUpOwnerResourcesAndStopsOfferingDisabledMcp(
            @org.junit.jupiter.api.io.TempDir java.nio.file.Path root) throws Exception {
        try (var mcp = new McpRuntimeTest.Fixture()) {
            var owner = UUID.randomUUID();
            var otherOwner = UUID.randomUUID();
            var agents = new AgentRegistryConfiguration().agentRegistry();
            var authorization = new com.mindrails.backend.mcp.service.McpToolAuthorization(
                    agents, mcp.registry);
            var mcpTools = new com.mindrails.backend.mcp.api.toolspec.McpTools(
                    new com.mindrails.backend.mcp.service.DefaultMcpService(
                            mcp.registry, mcp.manager, new com.mindrails.backend.mcp.service.internal.McpSecrets(""),
                            mcp.props, TestHistory.disabled(), authorization));
            var requests = new ArrayList<ChatRequest>();
            var model =
                    new ChatModel() {
                        @Override
                        public ChatResponse doChat(ChatRequest request) {
                            requests.add(request);
                            return ChatResponse.builder()
                                    .aiMessage(AiMessage.from("[\"ok\"]"))
                                    .build();
                        }
                    };
            var factory =
                    TestLlmClients.create(
                            List.of(),
                            InMemoryChatMemoryStore::new,
                            new ChatMemoryProperties(),
                            null,
                            mcpTools,
                            root);
            var session =
                    Session.builder()
                            .id(UUID.randomUUID())
                            .ownerId(owner)
                            .memoryId(UUID.randomUUID())
                            .provider(
                                    Provider.builder()
                                            .id(UUID.randomUUID())
                                            .streaming(false)
                                            .chatModel(model)
                                            .build())
                            .build();
            var sessions = new SessionRegistryConfiguration().sessionRegistry();
            sessions.insertIfAbsent(session);
            var chat = TestSessionChat.service(sessions, factory, TestHistory.disabled(), TestMetrics.disabled());
            assertInstanceOf(ServiceResult.Success.class, TestSessionChat.chat(chat, session.getId(), "first"));
            writeUserSkill(root, owner, "owner-skill");
            writeUserSkill(root, otherOwner, "other-skill");
            try (var ignored = com.mindrails.backend.identity.ResourceAccess.as(otherOwner)) {
                mcp.stdio("other-user", 30000);
            }
            try (var ignored = com.mindrails.backend.identity.ResourceAccess.as(owner)) {
                var server = mcp.stdio("newly-registered", 30000);
                assertInstanceOf(ServiceResult.Success.class, TestSessionChat.chat(chat, session.getId(), "second"));
                assertInstanceOf(ServiceResult.Success.class,
                        mcp.service.update(server.id(), server.version(), null, false));
                assertInstanceOf(ServiceResult.Success.class, TestSessionChat.chat(chat, session.getId(), "third"));
            }
            assertEquals(3, requests.size());
            assertFalse(hasTool(requests.get(0), "activate_skill"));
            assertTrue(hasTool(requests.get(1), "activate_skill"));
            assertTrue(requests.get(1).messages().toString().contains("owner-skill"));
            assertFalse(requests.get(1).messages().toString().contains("other-skill"));
            assertTrue(hasTool(requests.get(1), "MCP server newly-registered; tool add."));
            assertFalse(hasTool(requests.get(1), "MCP server other-user;"));
            assertFalse(hasTool(requests.get(2), "MCP server newly-registered;"));
        }
    }

    private static void writeUserSkill(java.nio.file.Path root, UUID owner, String name) throws Exception {
        var folder = java.nio.file.Files.createDirectories(root.resolve("skill").resolve(owner.toString()).resolve(name));
        java.nio.file.Files.writeString(folder.resolve("SKILL.md"),
                "---\nname: " + name + "\ndescription: Test catalog refresh\n---\nTest instructions.");
    }

    private static boolean hasTool(ChatRequest request, String text) {
        return request.parameters().toolSpecifications().stream()
                .anyMatch(tool -> tool.name().equals(text)
                        || tool.description() != null && tool.description().startsWith(text));
    }

    @Test
    void cancellingAChatFencesAnIgnoredCancellationAndLeavesTheNextTurnIntact() throws Exception {
        var eventFile = java.nio.file.Files.createTempFile("mcp-cancel-", ".jsonl");
        try (var mcp = new McpRuntimeTest.Fixture();
                var preparation =
                        Executors.newSingleThreadExecutor(Thread.ofPlatform().daemon().factory())) {
            mcp.service.importConfig(
                    Map.of(
                            "servers",
                            Map.of(
                                    "late-mock",
                                    Map.of(
                                            "command",
                                            "python3",
                                            "args",
                                            List.of(
                                                    McpRuntimeTest.SCRIPT,
                                                    "--ignore-cancel",
                                                    "--events",
                                                    eventFile.toString()),
                                            "protocolVersion",
                                            "2025-11-25"))),
                    "CREATE_ONLY",
                    null);
            var calls = new AtomicInteger();
            var model =
                    new StreamingChatModel() {
                        @Override
                        public void doChat(
                                ChatRequest request, StreamingChatResponseHandler handler) {
                            if (calls.getAndIncrement() == 0) {
                                var tool =
                                        request.parameters().toolSpecifications().stream()
                                                .filter(t -> t.description().contains("tool slow."))
                                                .findFirst()
                                                .orElseThrow();
                                handler.onCompleteResponse(
                                        ChatResponse.builder()
                                                .aiMessage(
                                                        AiMessage.from(
                                                                ToolExecutionRequest.builder()
                                                                        .id("late-call")
                                                                        .name(tool.name())
                                                                        .arguments(
                                                                                "{\"seconds\":1.5,\"text\":\"late-result\"}")
                                                                        .build()))
                                                .finishReason(FinishReason.TOOL_EXECUTION)
                                                .build());
                            } else {
                                for (var message : request.messages()) {
                                    if (message instanceof AiMessage ai) {
                                        for (var call : ai.toolExecutionRequests()) {
                                            assertTrue(request.messages().stream().anyMatch(result ->
                                                    result instanceof dev.langchain4j.data.message.ToolExecutionResultMessage toolResult
                                                    && java.util.Objects.equals(call.id(), toolResult.id())));
                                        }
                                    }
                                }
                                handler.onCompleteResponse(
                                        ChatResponse.builder()
                                                .aiMessage(AiMessage.from("new answer"))
                                                .finishReason(FinishReason.STOP)
                                                .build());
                            }
                        }
                    };
            var memory = new java.util.concurrent.atomic.AtomicReference<InMemoryChatMemoryStore>();
            var factory =
                    TestLlmClients.create(
                            List.of(),
                            () -> {
                                var store = new InMemoryChatMemoryStore();
                                memory.set(store);
                                return store;
                            },
                            new ChatMemoryProperties(),
                            null,
                            mcp.tools);
            var sessions = new SessionRegistryConfiguration().sessionRegistry();
            var session =
                    Session.builder()
                            .id(UUID.randomUUID())
                            .memoryId(UUID.randomUUID())
                            .provider(
                                    Provider.builder()
                                            .id(UUID.randomUUID())
                                            .streaming(true)
                                            .streamingChatModel(model)
                                            .build())
                            .build();
            sessions.insertIfAbsent(session);
            var service =
                    TestSessionChat.service(
                            sessions,
                            factory,
                            TestHistory.disabled(),
                            TestMetrics.disabled());
            var events = new CopyOnWriteArrayList<ChatStreamEvent>();
            assertInstanceOf(
                    ServiceResult.Success.class,
                    TestSessionChat.start(service,
                            session.getId(),
                            new ClientCommand.Chat(UUID.randomUUID(), "slow call"),
                            events::add));
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            while (!java.nio.file.Files.readString(eventFile).contains("tools/call")
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            assertTrue(java.nio.file.Files.readString(eventFile).contains("tools/call"));
            assertInstanceOf(
                    ServiceResult.Success.class,
                    service.stop(session.getId()));
            var next = new CountDownLatch(1);
            assertInstanceOf(
                    ServiceResult.Success.class,
                    TestSessionChat.start(service,
                            session.getId(),
                            new ClientCommand.Chat(UUID.randomUUID(), "new turn"),
                            event -> {
                                if (event instanceof ChatStreamEvent.Completed) {
                                    next.countDown();
                                }
                            }));
            assertTrue(next.await(5, TimeUnit.SECONDS));
            Thread.sleep(1700);
            assertEquals(2, calls.get());
            assertFalse(events.stream().anyMatch(ChatStreamEvent.ToolCompleted.class::isInstance));
            assertTrue(
                    memory.get().getMessages(session.getMemoryId()).stream()
                            .anyMatch(
                                    message ->
                                            message instanceof AiMessage ai
                                                    && "new answer".equals(ai.text())));
            assertFalse(
                    memory.get().getMessages(session.getMemoryId()).stream()
                            .filter(dev.langchain4j.data.message.ToolExecutionResultMessage.class::isInstance)
                            .map(dev.langchain4j.data.message.ToolExecutionResultMessage.class::cast)
                            .anyMatch(message -> message.text().contains("late-result")));
            assertTrue(
                    java.nio.file.Files.readString(eventFile).contains("notifications/cancelled"));
        } finally {
            java.nio.file.Files.deleteIfExists(eventFile);
        }
    }
}
