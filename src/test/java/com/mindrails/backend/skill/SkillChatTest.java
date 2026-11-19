package com.mindrails.backend.skill;

import com.mindrails.backend.agent.service.internal.TestSessionChat;

import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.SessionRegistryConfiguration;
import com.mindrails.backend.agent.service.internal.DefaultSessionService;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.identity.IdentityService;
import com.mindrails.backend.memory.ChatMemoryProperties;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.tool.IdentifiedTool;
import com.mindrails.backend.tool.spec.BashTool;
import com.mindrails.backend.tool.spec.WriteTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class SkillChatTest {
    @TempDir Path temp;

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void nativeActivationAndResourcesWorkAlongsideLocalToolsInRestAndStreaming(boolean streaming) throws Exception {
        var directory = skill(temp, "example", "Use the reference, then call ping. PRIVATE INSTRUCTIONS");
        Files.createDirectories(directory.resolve("references"));
        Files.writeString(directory.resolve("references/info.md"), "REFERENCE CONTENT");
        var step = new AtomicInteger();
        Function<ChatRequest, ChatResponse> model = request -> {
            assertTrue(hasTool(request, "activate_skill"));
            assertTrue(hasTool(request, "read_skill_resource"));
            assertTrue(hasTool(request, "ping"));
            assertFalse(hasTool(request, "skill_load"));
            assertFalse(hasTool(request, "skill_publish"));
            var system = (SystemMessage) request.messages().getFirst();
            assertTrue(system.text().startsWith("Literal {{instruction}} stays"));
            assertTrue(system.text().contains("<name>example</name>"));
            return switch (step.getAndIncrement()) {
                case 0 -> {
                    assertFalse(request.messages().toString().contains("PRIVATE INSTRUCTIONS"));
                    yield call("activate_skill", Map.of("skill_name", "example"));
                }
                case 1 -> {
                    assertTrue(request.messages().toString().contains("PRIVATE INSTRUCTIONS"));
                    yield call("read_skill_resource", Map.of("skill_name", "example", "relative_path", "references/info.md"));
                }
                case 2 -> {
                    assertTrue(request.messages().toString().contains("REFERENCE CONTENT"));
                    yield call("ping", Map.of());
                }
                default -> reply(streaming ? "done" : "[\"done\"]");
            };
        };
        var archive = new ArrayList<HistoryRecord>();
        var history = TestHistory.create(TestHistory.collect(archive), null);
        var factory = TestLlmClients.create(List.of(new Ping()), InMemoryChatMemoryStore::new,
                new ChatMemoryProperties(), null, null, temp, null, history, TestMetrics.disabled());
        var sessions = new SessionRegistryConfiguration().sessionRegistry();
        var session = session(model, streaming);
        // Bind only skills owned by this Session user.
        sessions.insertIfAbsent(session);
        if (streaming) {
            var events = new java.util.concurrent.CopyOnWriteArrayList<ChatStreamEvent>();
            var service = TestSessionChat.service(sessions, factory, history, TestMetrics.disabled());
            success(TestSessionChat.start(service, session.getId(), new ClientCommand.Chat(UUID.randomUUID(), "Use example"), events::add));
            TestSessionChat.awaitTerminal(events);
            assertInstanceOf(ChatStreamEvent.Completed.class, events.getLast());
            assertEquals(3, events.stream().filter(ChatStreamEvent.ToolCompleted.class::isInstance).count());
        } else {
            var service = TestSessionChat.service(sessions, factory, history, TestMetrics.disabled());
            assertEquals("[\"done\"]", success(TestSessionChat.chat(service, session.getId(), "Use example")).answer());
        }
        assertEquals(4, step.get());
        assertEquals(1, archive.size());
        assertTrue(archive.getFirst().messages().contains("activate_skill"));
        assertTrue(archive.getFirst().messages().contains("REFERENCE CONTENT"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void fileToolCanCreateASkillThatCachedAndNewClientsLoadOnTheNextTurn(boolean streaming) throws Exception {
        var target = temp.resolve("skill").resolve(IdentityService.ADMIN_ID.toString()).resolve("generated/SKILL.md");
        String content = markdown("generated", "GENERATED INSTRUCTIONS");
        var creationStep = new AtomicInteger();
        var creator = session(request -> {
            var system = ((SystemMessage) request.messages().getFirst()).text();
            assertTrue(system.contains("downloadUrl"));
            assertTrue(system.contains(target.getParent().getParent().toString()));
            int step = creationStep.getAndIncrement();
            if (step < 2) {
                assertFalse(hasTool(request, "activate_skill"));
                if (step == 0) return call("write", Map.of("path", target.toString(), "content", content));
                return reply(streaming ? "created" : "[\"created\"]");
            }
            assertTrue(system.contains("<name>generated</name>"));
            assertTrue(hasTool(request, "activate_skill"));
            assertTrue(request.messages().toString().contains("Create a skill"));
            if (step == 2) return call("activate_skill", Map.of("skill_name", "generated"));
            assertTrue(request.messages().toString().contains("GENERATED INSTRUCTIONS"));
            return reply(streaming ? "activated" : "[\"activated\"]");
        }, streaming);
        var factory = factory(List.of(new WriteTool(temp)), temp);
        var originalClient = success(factory.createClientForLoop(creator));
        invoke(originalClient, creator, streaming, "Create a skill");
        assertEquals(content, Files.readString(target));
        invoke(originalClient, creator, streaming, "Use generated skill");
        assertEquals(4, creationStep.get());

        var activationStep = new AtomicInteger();
        var otherSession = session(request -> {
            assertTrue(hasTool(request, "activate_skill"));
            assertTrue(request.messages().getFirst().toString().contains("<name>generated</name>"));
            if (activationStep.getAndIncrement() == 0) return call("activate_skill", Map.of("skill_name", "generated"));
            assertTrue(request.messages().toString().contains("GENERATED INSTRUCTIONS"));
            return reply("[\"activated\"]");
        }, false);
        assertEquals("[\"activated\"]", TestLlmClients.chat(success(factory.createClientForLoop(otherSession)), otherSession.getMemoryId(), "Use generated skill").content());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void reusedClientsReadEditedSkillsAndRemoveDeletedSkills(boolean streaming) throws Exception {
        var directory = skill(temp, "example", "OLD INSTRUCTIONS");
        var requests = new ArrayList<ChatRequest>();
        var step = new AtomicInteger();
        var session = session(request -> {
            requests.add(request);
            return switch (step.getAndIncrement()) {
                case 0 -> reply(streaming ? "ready" : "[\"ready\"]");
                case 1 -> call("activate_skill", Map.of("skill_name", "example"));
                case 2 -> {
                    assertTrue(request.messages().toString().contains("UPDATED INSTRUCTIONS"));
                    assertFalse(request.messages().toString().contains("OLD INSTRUCTIONS"));
                    yield reply(streaming ? "updated" : "[\"updated\"]");
                }
                default -> {
                    assertFalse(hasTool(request, "activate_skill"));
                    assertFalse(hasTool(request, "read_skill_resource"));
                    assertFalse(((SystemMessage) request.messages().getFirst()).text().contains("<name>example</name>"));
                    yield reply(streaming ? "removed" : "[\"removed\"]");
                }
            };
        }, streaming);
        var factory = factory(List.of(), temp);
        var client = success(factory.createClientForLoop(session));
        invoke(client, session, streaming, "First turn");
        assertTrue(hasTool(requests.getFirst(), "activate_skill"));

        skill(temp, "example", "UPDATED INSTRUCTIONS");
        invoke(client, session, streaming, "Activate edited skill");

        Files.delete(directory.resolve("SKILL.md"));
        Files.delete(directory);
        invoke(client, session, streaming, "After deletion");
        assertEquals(4, step.get());
    }

    private static void invoke(LlmClient client, Session session, boolean streaming, String message) {
        if (streaming) {
            client.chatWithStream(session.getMemoryId(), message).onError(error -> fail(error)).start();
        } else {
            TestLlmClients.chat(client, session.getMemoryId(), message);
        }
    }

    @Test
    void installsNestedRepositorySkillWithShellAndActivatesItOnTheNextTurn() throws Exception {
        Path source = Files.createDirectories(temp.resolve("checkout/skills/engineering/example"));
        Files.writeString(source.resolve("SKILL.md"), markdown("example", "COPIED INSTRUCTIONS"));
        Files.createDirectories(source.resolve("references"));
        Files.writeString(source.resolve("references/guide.md"), "COPIED REFERENCE");
        UUID owner = UUID.randomUUID();
        Path destination = temp.resolve("skill").resolve(owner.toString());
        var step = new AtomicInteger();
        var session = session(request -> {
            var system = ((SystemMessage) request.messages().getFirst()).text();
            assertTrue(system.contains(destination + "/<skill-name>/SKILL.md"));
            assertTrue(system.contains("copy each complete skill directory"));
            assertTrue(system.contains("Skip agent-specific installers"));
            return switch (step.getAndIncrement()) {
                case 0 -> {
                    assertFalse(hasTool(request, "activate_skill"));
                    assertTrue(request.parameters().toolSpecifications().stream()
                            .anyMatch(spec -> spec.name().equals("bash") && spec.description().contains("prefer ripgrep")));
                    yield call("bash", Map.of("path", temp.toString(), "command",
                            "mkdir -p '%s' && cp -R '%s' '%s/'".formatted(destination, source, destination)));
                }
                case 1 -> reply("[\"installed\"]");
                case 2 -> {
                    assertTrue(hasTool(request, "activate_skill"));
                    assertTrue(system.contains("<name>example</name>"));
                    yield call("activate_skill", Map.of("skill_name", "example"));
                }
                default -> {
                    assertTrue(request.messages().toString().contains("COPIED INSTRUCTIONS"));
                    yield reply("[\"activated\"]");
                }
            };
        }, false);
        session.setOwnerId(owner);
        var client = success(factory(List.of(new BashTool()), temp).createClientForLoop(session));
        assertEquals("[\"installed\"]", TestLlmClients.chat(client, session.getMemoryId(),
                "Install every skill from this repository").content());
        assertEquals("COPIED REFERENCE", Files.readString(destination.resolve("example/references/guide.md")));
        assertEquals("[\"activated\"]", TestLlmClients.chat(client, session.getMemoryId(),
                "Use the example skill").content());
        assertEquals(4, step.get());
    }

    private LlmClientFactory factory(List<IdentifiedTool> tools, Path storage) {
        return TestLlmClients.create(tools, InMemoryChatMemoryStore::new, new ChatMemoryProperties(), null, null, storage);
    }

    private static Path skill(Path root, String name, String instructions) throws Exception {
        var directory = Files.createDirectories(root.resolve("skill").resolve(IdentityService.ADMIN_ID.toString()).resolve(name));
        Files.writeString(directory.resolve("SKILL.md"), markdown(name, instructions));
        return directory;
    }

    private static String markdown(String name, String instructions) {
        return "---\nname: " + name + "\ndescription: Use for " + name + " tasks\n---\n" + instructions;
    }

    private static boolean hasTool(ChatRequest request, String name) {
        return request.parameters().toolSpecifications().stream().anyMatch(tool -> tool.name().equals(name));
    }

    private static ChatResponse call(String name, Map<String, ?> arguments) {
        return ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder()
                .id(UUID.randomUUID().toString()).name(name).arguments(new JsonMapper().writeValueAsString(arguments)).build())).build();
    }

    private static ChatResponse reply(String answer) { return ChatResponse.builder().aiMessage(AiMessage.from(answer)).build(); }

    private static Session session(Function<ChatRequest, ChatResponse> response, boolean streaming) {
        var provider = Provider.builder().id(UUID.randomUUID()).streaming(streaming)
                .chatModel(new ChatModel() { @Override public ChatResponse doChat(ChatRequest request) { return response.apply(request); } })
                .streamingChatModel(new StreamingChatModel() { @Override public void doChat(ChatRequest request, StreamingChatResponseHandler handler) { handler.onCompleteResponse(response.apply(request)); } })
                .build();
        return Session.builder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .systemMessage("Literal {{instruction}} stays").provider(provider).build();
    }

    private static <T> T success(ServiceResult<T> result) {
        assertInstanceOf(ServiceResult.Success.class, result);
        return ((ServiceResult.Success<T>) result).value();
    }

    static class Ping implements IdentifiedTool {
        @Override public UUID id() { return UUID.randomUUID(); }
        @Tool public String ping() { return "pong"; }
    }
}
