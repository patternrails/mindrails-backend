package com.mindrails.backend.agent.service.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.provider.Provider;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class LoopSessionClientIntegrationTest {
    @Test
    void syncProviderExecutesToolsThroughEventsAndPicksUpUpdatedSettingsWithoutRebinding() throws Exception {
        var tool = new Ping();
        var requests = new CopyOnWriteArrayList<ChatRequest>();
        ChatModel model = new ChatModel() {
            @Override public ChatResponse doChat(ChatRequest request) {
                requests.add(request);
                var reply = request.messages().getLast() instanceof ToolExecutionResultMessage
                        ? AiMessage.from("Tool finished")
                        : AiMessage.from("Checking", List.of(ToolExecutionRequest.builder()
                                .id("ping-1").name("ping").arguments("{}").build()));
                return ChatResponse.builder().aiMessage(reply).build();
            }
        };
        var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .parentAgentId(UUID.randomUUID())
                .provider(Provider.builder().streaming(false).chatModel(model).build()).build();
        var registry = mock(SessionRegistry.class);
        when(registry.findManaged(session.getId())).thenReturn(Optional.of(session));
        var store = new dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore();
        var factory = spy(com.mindrails.backend.agent.service.internal.llm.TestLlmClients.create(
                List.of(tool), () -> store, new com.mindrails.backend.memory.ChatMemoryProperties()));
        var service = new DefaultLoopSessionStreamService(registry, factory, TestHistory.disabled(), TestMetrics.disabled(), null);
        var events = new CopyOnWriteArrayList<ChatStreamEvent>();
        var terminals = new LinkedBlockingQueue<ChatStreamEvent>();
        try {
            service.bind(session.getId(), event -> {
                events.add(event);
                if (event instanceof ChatStreamEvent.Completed || event instanceof ChatStreamEvent.Failed) terminals.add(event);
            });
            service.enqueue(session.getId(), new ClientCommand.Chat(UUID.randomUUID(), "Check"));
            var first = assertInstanceOf(ChatStreamEvent.Completed.class, terminals.poll(5, TimeUnit.SECONDS));
            assertEquals("Tool finished", first.answer());
            assertEquals(1, tool.calls);
            var intermediate = events.stream().filter(ChatStreamEvent.ModelResponseCompleted.class::isInstance)
                    .map(ChatStreamEvent.ModelResponseCompleted.class::cast).findFirst().orElseThrow();
            assertEquals(1, intermediate.round());
            assertNotEquals(intermediate.messageId(), first.finalMessageId());
            var executed = events.stream().filter(ChatStreamEvent.ToolCompleted.class::isInstance)
                    .map(ChatStreamEvent.ToolCompleted.class::cast).findFirst().orElseThrow();
            assertEquals(1, executed.round());
            assertEquals("pong", executed.result());

            var updatedRequests = new CopyOnWriteArrayList<ChatRequest>();
            ChatModel updatedModel = new ChatModel() {
                @Override public ChatResponse doChat(ChatRequest request) {
                    updatedRequests.add(request);
                    return ChatResponse.builder().aiMessage(AiMessage.from("New model")).build();
                }
            };
            var updated = session.toBuilder().systemMessage("New instructions")
                    .provider(Provider.builder().streaming(false).chatModel(updatedModel).build()).build();
            when(registry.findManaged(session.getId())).thenReturn(Optional.of(updated));

            service.enqueue(session.getId(), new ClientCommand.Chat(UUID.randomUUID(), "Continue"));
            var second = assertInstanceOf(ChatStreamEvent.Completed.class, terminals.poll(5, TimeUnit.SECONDS));
            assertEquals("New model", second.answer());
            verify(factory, times(2)).createClientForLoop(any());
            assertEquals(2, requests.size());
            assertEquals(SystemMessage.from("New instructions"), updatedRequests.getFirst().messages().getFirst());
            assertTrue(updatedRequests.getFirst().messages().stream()
                    .anyMatch(message -> message instanceof AiMessage ai && "Tool finished".equals(ai.text())));
        } finally { service.shutdown(); }
    }

    @Test
    void businessStopKeepsQueuedTextAndStopNoticeInTheClientsMemory() throws Exception {
        record Request(ChatRequest request, dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {}
        var requests = new LinkedBlockingQueue<Request>();
        dev.langchain4j.model.chat.StreamingChatModel model = new dev.langchain4j.model.chat.StreamingChatModel() {
            @Override public void doChat(ChatRequest request,
                    dev.langchain4j.model.chat.response.StreamingChatResponseHandler handler) {
                requests.add(new Request(request, handler));
            }
        };
        var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).parentAgentId(UUID.randomUUID())
                .provider(Provider.builder().streaming(true).streamingChatModel(model).build()).build();
        var registry = mock(SessionRegistry.class);
        when(registry.findManaged(session.getId())).thenReturn(Optional.of(session));
        var service = new DefaultLoopSessionStreamService(registry, com.mindrails.backend.memory.TestMemory.factory(List.of()),
                TestHistory.disabled(), TestMetrics.disabled(), null);
        var terminals = new LinkedBlockingQueue<ChatStreamEvent>();
        try {
            service.bind(session.getId(), event -> {
                if (event instanceof ChatStreamEvent.Completed || event instanceof ChatStreamEvent.Failed) terminals.add(event);
            });
            service.enqueue(session.getId(), new ClientCommand.Chat(UUID.randomUUID(), "A"));
            assertNotNull(requests.poll(5, TimeUnit.SECONDS));
            service.enqueue(session.getId(), new ClientCommand.Chat(UUID.randomUUID(), "B"));
            service.enqueue(session.getId(), new ClientCommand.Chat(UUID.randomUUID(), "C"));
            service.stop(session.getId());
            service.enqueue(session.getId(), new ClientCommand.Chat(UUID.randomUUID(), "D"));
            var resumed = requests.poll(5, TimeUnit.SECONDS);
            assertNotNull(resumed);
            var text = resumed.request().messages().stream().filter(dev.langchain4j.data.message.UserMessage.class::isInstance)
                    .map(dev.langchain4j.data.message.UserMessage.class::cast)
                    .map(dev.langchain4j.data.message.UserMessage::singleText).toList();
            assertEquals(List.of("A", "B", "C"), text.subList(0, 3));
            assertTrue(text.get(3).contains("stopped"));
            assertEquals("D", text.getLast());
            assertEquals(5, text.size());
            resumed.handler().onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from("Resumed")).build());
            assertInstanceOf(ChatStreamEvent.Completed.class, terminals.poll(5, TimeUnit.SECONDS));
            assertTrue(requests.isEmpty());
        } finally { service.shutdown(); }
    }

    public static class Ping implements IdentifiedTool {
        private final UUID id = UUID.randomUUID();
        int calls;
        @Override public UUID id() { return id; }
        @Tool("Returns pong") public String ping() { calls++; return "pong"; }
    }
}
