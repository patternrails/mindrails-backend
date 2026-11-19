package com.mindrails.backend.memory;

import static dev.langchain4j.data.message.ChatMessageDeserializer.messagesFromJson;
import static dev.langchain4j.data.message.ChatMessageSerializer.messagesToJson;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.store.memory.chat.ChatMemoryStore;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Persists the current LC4J message window, rather than an append-only transcript. */
public final class MysqlChatMemoryStore implements ChatMemoryStore {
    private final JdbcTemplate jdbc;

    public MysqlChatMemoryStore(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc);
    }

    @Override
    public List<ChatMessage> getMessages(Object memoryId) {
        var rows = jdbc.query("SELECT messages FROM chat_memory WHERE memory_id = ?",
                (rs, row) -> rs.getString(1), key(memoryId));
        return rows.isEmpty() ? List.of() : messagesFromJson(rows.getFirst());
    }

    @Override
    public void updateMessages(Object memoryId, List<ChatMessage> messages) {
        jdbc.update("""
                INSERT INTO chat_memory (memory_id, messages) VALUES (?, ?) AS incoming
                ON DUPLICATE KEY UPDATE messages = incoming.messages,
                    updated_at = CURRENT_TIMESTAMP(6)
                """, key(memoryId), messagesToJson(Objects.requireNonNull(messages)));
    }

    @Override
    public void deleteMessages(Object memoryId) {
        jdbc.update("DELETE FROM chat_memory WHERE memory_id = ?", key(memoryId));
    }

    private static String key(Object memoryId) {
        if (!(memoryId instanceof UUID id)) {
            throw new IllegalArgumentException("Chat memory ID must be a UUID");
        }
        return id.toString();
    }
}
