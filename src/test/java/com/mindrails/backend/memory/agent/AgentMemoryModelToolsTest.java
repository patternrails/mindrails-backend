package com.mindrails.backend.memory.agent;

import com.mindrails.backend.agent.service.internal.TestSessionChat;

import com.mindrails.backend.metrics.TestMetrics;

import com.mindrails.backend.history.TestHistory;

import static com.mindrails.backend.memory.agent.AgentMemoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService;
import com.mindrails.backend.tool.spec.AgentMemoryTools;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class AgentMemoryModelToolsTest {
    @ParameterizedTest @ValueSource(booleans = {false, true})
    void modelCreatesMemoryBeforeFinalAnswerAndServerAssignsScopeAndProvenance(boolean streaming) throws Exception {
        var f = new Fixture();
        UUID owner = f.agent(), other = f.agent();
        var requests = new CopyOnWriteArrayList<ChatRequest>();
        var createArgs = "{\"key\":\"response.language\",\"type\":\"PREFERENCE\",\"content\":\"以后默认用中文回答\",\"tags\":[\"语言\",\"回答\"],"
                + "\"agentId\":\"" + other + "\",\"memoryId\":\"" + success(f.agents.get(other)).getMemoryId()
                + "\",\"sourceSessionId\":\"" + UUID.randomUUID() + "\",\"pinned\":true,\"priority\":100}";
        install(f, streaming, request -> {
            requests.add(request);
            if (requests.size() == 1) return call("agent_memory_create", createArgs);
            if (requests.size() == 2) {
                var result = lastTool(request);
                assertTrue(result.get("success").asBoolean());
                assertEquals("CREATED", result.get("status").asText());
            }
            return reply();
        });
        UUID session = f.session(owner);
        var events = run(f, streaming, owner, session, "以后默认用中文回答，请记住");
        var entry = success(f.memory.getByKey(owner, "response.language"));
        assertEquals(session, entry.sourceSessionId());
        assertEquals(owner, entry.agentId());
        assertFalse(entry.pinned());
        assertEquals(0, entry.priority());
        assertTrue(success(f.memory.list(other, 0, 20)).isEmpty());
        var memorySpecs = requests.getFirst().parameters().toolSpecifications().stream()
                .filter(spec -> AgentMemoryTools.isMemoryTool(spec.name())).toList();
        assertEquals(5, memorySpecs.size());
        memorySpecs.forEach(spec -> assertTrue(java.util.Collections.disjoint(spec.parameters().properties().keySet(),
                Set.of("agentId", "memoryId", "sourceSessionId", "context", "pinned", "priority"))));
        assertEquals(2, requests.size());
        if (streaming) {
            assertTrue(events.stream().anyMatch(ChatStreamEvent.ToolStarted.class::isInstance));
            assertTrue(events.stream().anyMatch(ChatStreamEvent.ToolCompleted.class::isInstance));
        }
        UUID second = f.session(owner);
        // No words in this input match the unpinned preference, its key, or tags.
        run(f, streaming, owner, second, "你好");
        assertTrue(lastUser(requests.getLast()).contains(entry.content()));
        assertFalse(requests.getLast().messages().toString().contains("请记住"));
        run(f, streaming, owner, session, "今天天气怎么样？");
        assertTrue(lastUser(requests.getLast()).contains(entry.content()));
        success(f.memory.update(owner, entry.id(), new AgentMemoryInput(entry.key(), entry.type(),
                "以后默认用英文回答", entry.tags(), false, 0, session, 0L)));
        run(f, streaming, owner, session, "你好");
        assertTrue(lastUser(requests.getLast()).contains("以后默认用英文回答"));
        assertFalse(lastUser(requests.getLast()).contains(entry.content()));
        success(f.memory.delete(owner, entry.id(), 1));
        run(f, streaming, owner, session, "你好");
        assertFalse(lastUser(requests.getLast()).contains(AgentMemoryRetrieval.PREFIX));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void conflictsAndMalformedArgumentsReturnStructuredFailuresAndModelCanRepairThem(boolean streaming) throws Exception {
        var f = new Fixture();
        UUID owner = f.agent();
        var initial = success(f.memory.create(owner, input("database", "Old database fact", false, null)));
        var requests = new CopyOnWriteArrayList<ChatRequest>();
        var index = new AtomicInteger();
        install(f, streaming, request -> {
            requests.add(request);
            int step = index.getAndIncrement();
            return switch (step) {
                case 0 -> call("agent_memory_create", "{\"key\":\"database\",\"content\":\"New database fact\"}");
                case 1 -> {
                    assertEquals("CONFLICT", lastTool(request).get("code").asText());
                    yield call("agent_memory_get", "{\"key\":\"database\"}");
                }
                case 2 -> {
                    assertEquals(0, lastTool(request).get("memories").get(0).get("version").asInt());
                    yield call("agent_memory_update", "{\"key\":\"database\",\"type\":\"FACT\",\"content\":\"New database fact\",\"expectedVersion\":\"invalid\"}");
                }
                case 3 -> {
                    assertEquals("INVALID_ARGUMENT", lastTool(request).get("code").asText());
                    yield call("agent_memory_update", "{\"key\":\"database\",\"type\":\"FACT\",\"content\":\"New database fact\",\"expectedVersion\":0}");
                }
                case 4 -> {
                    assertEquals("UPDATED", lastTool(request).get("status").asText());
                    yield call("agent_memory_search", "{\"query\":\"database\"}");
                }
                default -> {
                    assertEquals("RESULTS", lastTool(request).get("status").asText());
                    yield reply();
                }
            };
        });
        UUID session = f.session(owner);
        var events = run(f, streaming, owner, session, "数据库事实已经变更，请更新记忆");
        var updated = success(f.memory.get(owner, initial.id()));
        assertEquals("New database fact", updated.content());
        assertEquals(1, updated.version());
        assertEquals(session, updated.sourceSessionId());
        assertEquals(6, requests.size());
        if (streaming) assertTrue(events.stream().anyMatch(ChatStreamEvent.ToolFailed.class::isInstance));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void modelReadsVersionAndForgetsMemoryAtUsersRequest(boolean streaming) throws Exception {
        var f = new Fixture();
        UUID owner = f.agent();
        success(f.memory.create(owner, input("preference", "Forget this", false, null)));
        var index = new AtomicInteger();
        install(f, streaming, request -> switch (index.getAndIncrement()) {
            case 0 -> call("agent_memory_get", "{\"key\":\"preference\"}");
            case 1 -> {
                assertEquals(0, lastTool(request).get("memories").get(0).get("version").asInt());
                yield call("agent_memory_delete", "{\"key\":\"preference\",\"expectedVersion\":0}");
            }
            default -> {
                assertEquals("DELETED", lastTool(request).get("status").asText());
                yield reply();
            }
        });
        UUID session = f.session(owner);
        run(f, streaming, owner, session, "请忘记 preference 这条记忆");
        assertTrue(success(f.memory.list(owner, 0, 20)).isEmpty());
        assertTrue(f.sessionRegistry.findManaged(session).isPresent());
        assertFalse(f.chatStore.getMessages(f.sessionRegistry.findManaged(session).orElseThrow().getMemoryId()).isEmpty());
    }

    @Test void enabledMemoryAlwaysProvidesToolsAndInstructions() {
        var f = new Fixture();
        UUID owner = f.agent(), session = f.session(owner);
        success(f.memory.create(owner, input("manual", "Manually saved", false, null)));
        success(TestSessionChat.chat(f.streams, session, "Request"));
        var request = f.model.requests.getFirst();
        assertTrue(request.parameters().toolSpecifications().stream().anyMatch(spec -> AgentMemoryTools.isMemoryTool(spec.name())));
        assertEquals(SystemMessage.from("Literal {{instruction}}"), request.messages().getFirst());
        assertTrue(request.toolSpecifications().stream().anyMatch(tool -> tool.description().contains(AgentMemoryTools.INSTRUCTIONS)));
        assertTrue(lastUser(request).contains("Manually saved"));
    }

    @ParameterizedTest @ValueSource(booleans = {false, true})
    void storageWriteFailureIsReportedToModelWithoutPretendingItWasSaved(boolean streaming) throws Exception {
        var store = new InMemoryAgentMemoryStore() {
            @Override public void insert(AgentMemoryEntry entry) { throw new IllegalStateException("Internal store diagnostic"); }
        };
        var f = new Fixture(store);
        UUID owner = f.agent();
        var index = new AtomicInteger();
        install(f, streaming, request -> {
            if (index.getAndIncrement() == 0) return call("agent_memory_create", "{\"key\":\"fact\",\"content\":\"Could not save\"}");
            var result = lastTool(request);
            assertFalse(result.get("success").asBoolean());
            assertEquals("INTERNAL_ERROR", result.get("code").asText());
            assertFalse(result.toString().contains("Internal store diagnostic"));
            return reply();
        });
        UUID session = f.session(owner);
        var events = run(f, streaming, owner, session, "请记住一个事实");
        if (streaming) assertTrue(events.stream().anyMatch(ChatStreamEvent.ToolFailed.class::isInstance));
        assertTrue(success(f.memory.list(owner, 0, 20)).isEmpty());
    }

    @Test void blankSessionInstructionUsesOnlyMemoryGuidanceAndNeverRevivesOldInstruction() {
        var f = new Fixture();
        UUID owner = f.agent(), session = f.session(owner);
        success(TestSessionChat.chat(f.streams, session, "First"));
        success(f.agents.updateSession(owner, session, new com.mindrails.backend.agent.model.Session(), f.provider.getId()));
        success(TestSessionChat.chat(f.streams, session, "No literal instruction"));
        assertTrue(f.model.requests.getLast().messages().stream().noneMatch(SystemMessage.class::isInstance));
        assertTrue(f.model.requests.getLast().toolSpecifications().stream().anyMatch(tool -> tool.description().contains(AgentMemoryTools.INSTRUCTIONS)));
    }

    @Test void cancelledStreamsRejectLateModelMemoryToolCallsAndNewRunsRemainUsable() {
        var f = new Fixture();
        var handlers = new CopyOnWriteArrayList<StreamingChatResponseHandler>();
        f.provider.setStreaming(true);
        f.provider.setStreamingChatModel(new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { handlers.add(handler); }
        });
        UUID owner = f.agent(), session = f.session(owner);
        var streams = TestSessionChat.service(f.sessionRegistry, f.factory, TestHistory.disabled(), TestMetrics.disabled());
        success(TestSessionChat.start(streams, session, new ClientCommand.Chat(UUID.randomUUID(), "Cancelled"), event -> {}));
        TestSessionChat.await(() -> handlers.size() == 1);
        success(streams.stop(session));
        assertThrows(CancellationException.class, () -> handlers.getFirst().onCompleteResponse(
                call("agent_memory_create", "{\"key\":\"late\",\"content\":\"No late write\"}")));
        assertTrue(success(f.memory.list(owner, 0, 20)).isEmpty());
        success(TestSessionChat.start(streams, session, new ClientCommand.Chat(UUID.randomUUID(), "New"), event -> {}));
        TestSessionChat.await(() -> handlers.size() == 2);
        handlers.getLast().onCompleteResponse(reply());
        assertTrue(success(f.memory.list(owner, 0, 20)).isEmpty());
    }

    static void install(Fixture f, boolean streaming, java.util.function.Function<ChatRequest, ChatResponse> respond) {
        f.provider.setStreaming(streaming);
        f.provider.setChatModel(new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) { return respond.apply(request); }
        });
        f.provider.setStreamingChatModel(new StreamingChatModel() {
            @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { handler.onCompleteResponse(respond.apply(request)); }
        });
    }
    static List<ChatStreamEvent> run(Fixture f, boolean streaming, UUID owner, UUID session, String message) throws Exception {
        var events = new CopyOnWriteArrayList<ChatStreamEvent>();
        if (!streaming) success(TestSessionChat.chat(f.streams, session, message));
        else {
            var finished = new CountDownLatch(1);
            success(TestSessionChat.start(TestSessionChat.service(f.sessionRegistry, f.factory, TestHistory.disabled(), TestMetrics.disabled()), session,
                    new ClientCommand.Chat(UUID.randomUUID(), message), event -> {
                        events.add(event);
                        if (event instanceof ChatStreamEvent.Completed || event instanceof ChatStreamEvent.Failed) finished.countDown();
                    }));
            assertTrue(finished.await(5, TimeUnit.SECONDS));
            assertInstanceOf(ChatStreamEvent.Completed.class, events.getLast());
        }
        return events;
    }
}
