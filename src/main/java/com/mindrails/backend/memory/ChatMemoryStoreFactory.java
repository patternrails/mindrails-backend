package com.mindrails.backend.memory;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component("chatMemoryStoreFactory")
@EnableConfigurationProperties(ChatMemoryProperties.class)
public class ChatMemoryStoreFactory {
    private final int maxMessages;
    private final ChatMemoryStore store;

    @Autowired
    public ChatMemoryStoreFactory(
            ChatMemoryProperties properties, @Qualifier("memoryJdbcTemplate") JdbcTemplate jdbc) {
        this(properties, new MysqlChatMemoryStore(jdbc));
    }

    ChatMemoryStoreFactory(ChatMemoryProperties properties, ChatMemoryStore store) {
        this.maxMessages = properties.getMaxMessages();
        this.store = store;
    }

    /** A Task tool loop uses the configured window but never persists cross-execution context. */
    public ChatMemory buildTransientChatMemory(UUID executionId) {
        return MessageWindowChatMemory.builder().id(executionId).maxMessages(maxMessages)
                .alwaysKeepSystemMessageFirst(true).chatMemoryStore(new InMemoryChatMemoryStore()).build();
    }

    public ChatMemory buildChatMemory(UUID memoryId) {
        return MessageWindowChatMemory.builder()
                .id(memoryId)
                .maxMessages(maxMessages)
                .alwaysKeepSystemMessageFirst(true)
                .chatMemoryStore(store)
                .build();
    }

    /** Reads and writes the stored window directly, without applying the current window limit. */
    public List<ChatMessage> snapshot(UUID memoryId) {
        return List.copyOf(store.getMessages(memoryId));
    }

    public void restore(UUID memoryId, List<ChatMessage> messages) {
        store.updateMessages(memoryId, List.copyOf(messages));
    }

    public void delete(UUID memoryId) {
        store.deleteMessages(memoryId);
    }
}
