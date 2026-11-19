package com.mindrails.backend.memory;

import static com.mindrails.backend.memory.SessionMemoryLifecycleTest.*;
import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.message.AiMessage;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.UUID;

class LlmClientMemoryTest {
    @Test void clientOwnsWindowSizeAndKeepsSystemMessageFirst() {
        var properties = new ChatMemoryProperties();
        properties.setMaxMessages(3);
        var store = new dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore();
        var clients = TestLlmClients.create(List.of(), () -> store, properties);
        var model = new RecordingModel();
        var session = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID())
                .systemMessage("Instructions").provider(provider(model)).build();
        var client = success(clients.createClientForLoop(session));
        TestLlmClients.chat(client, session.getMemoryId(), "Old question");
        TestLlmClients.chat(client, session.getMemoryId(), "New question");
        var stored = store.getMessages(session.getMemoryId());
        assertEquals(3, stored.size());
        assertEquals(SystemMessage.from("Instructions"), stored.getFirst());
        assertTrue(stored.get(1).toString().contains("New question"));
        assertEquals(AiMessage.from("[\"reply\"]"), stored.getLast());
        assertFalse(stored.toString().contains("Old question"));
    }

    @Test void clientsUseIndependentMemoryIdsAndRetainMemoryAcrossRecreation() {
        var properties = new ChatMemoryProperties();
        var store = new dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore();
        var clients = TestLlmClients.create(List.of(), () -> store, properties);
        var model = new RecordingModel();
        var first = Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).provider(provider(model)).build();
        var second = first.toBuilder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).build();
        var firstClient = success(clients.createClientForLoop(first));
        TestLlmClients.chat(firstClient, first.getMemoryId(), "First");
        assertNotSame(firstClient, success(clients.createClientForLoop(first)));
        TestLlmClients.chat(firstClient, first.getMemoryId(), "Continue");
        assertEquals(3, model.requests.getLast().messages().size());
        TestLlmClients.chat(success(clients.createClientForLoop(second)), second.getMemoryId(), "Second");
        assertEquals(1, model.requests.getLast().messages().size());
        assertTrue(((UserMessage) model.requests.getLast().messages().getFirst()).singleText().startsWith("Second"));

        var recreated = success(clients.createClientForLoop(first));
        assertNotSame(firstClient, recreated);
        TestLlmClients.chat(recreated, first.getMemoryId(), "Recreated");
        assertEquals(5, model.requests.getLast().messages().size());
    }
}
