package com.mindrails.backend.memory;

import com.mindrails.backend.agent.service.internal.llm.TestLlmClients;

import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.util.List;
import java.util.UUID;

/** Unit tests explicitly inject an isolated store instead of relying on production configuration. */
public final class TestMemory {
    public static final UUID ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private TestMemory() {}

    public static ChatMemoryStoreFactory inMemory(int maxMessages) {
        var properties = new ChatMemoryProperties();
        properties.setMaxMessages(maxMessages);
        return inMemory(properties);
    }

    public static ChatMemoryStoreFactory inMemory(ChatMemoryProperties properties) {
        return new ChatMemoryStoreFactory(properties, new InMemoryChatMemoryStore());
    }

    @org.springframework.boot.test.context.TestConfiguration(proxyBeanMethods = false)
    public static class Wiring {
        @org.springframework.context.annotation.Bean
        @org.springframework.context.annotation.Primary
        @org.springframework.beans.factory.annotation.Qualifier("chatMemoryStoreFactory")
        ChatMemoryStoreFactory testChatMemoryStoreFactory(ChatMemoryProperties properties) {
            return inMemory(properties);
        }
    }

    public static LlmClientFactory factory(List<IdentifiedTool> tools) {
        return TestLlmClients.create(tools, InMemoryChatMemoryStore::new, new ChatMemoryProperties());
    }
}
