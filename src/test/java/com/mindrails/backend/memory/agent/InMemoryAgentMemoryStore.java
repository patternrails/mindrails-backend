package com.mindrails.backend.memory.agent;

import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DuplicateKeyException;

public class InMemoryAgentMemoryStore implements AgentMemoryStore {
    private final Map<UUID, AgentMemoryEntry> entries = new HashMap<>();

    @Override public synchronized List<AgentMemoryEntry> entries(UUID memoryId) {
        return entries.values().stream().filter(e -> memoryId.equals(e.memoryId()))
                .sorted(Comparator.comparing(AgentMemoryEntry::createdAt).thenComparing(e -> e.id().toString())).toList();
    }

    @Override public synchronized Optional<AgentMemoryEntry> find(UUID memoryId, UUID id) {
        return Optional.ofNullable(entries.get(id)).filter(e -> memoryId.equals(e.memoryId()));
    }

    @Override public synchronized Optional<AgentMemoryEntry> findByKey(UUID memoryId, String key) {
        return entries.values().stream().filter(e -> memoryId.equals(e.memoryId()) && e.key().equals(key)).findFirst();
    }

    @Override public synchronized void insert(AgentMemoryEntry entry) {
        checkKey(entry);
        if (entries.putIfAbsent(entry.id(), entry) != null) throw new DuplicateKeyException("Memory ID already exists");
    }

    @Override public synchronized boolean update(AgentMemoryEntry entry, long expectedVersion) {
        var current = find(entry.memoryId(), entry.id()).orElse(null);
        if (current == null || current.version() != expectedVersion) return false;
        checkKey(entry);
        entries.put(entry.id(), entry);
        return true;
    }

    @Override public synchronized boolean delete(UUID memoryId, UUID id, long expectedVersion) {
        var current = find(memoryId, id).orElse(null);
        return current != null && current.version() == expectedVersion && entries.remove(id, current);
    }

    @Override public synchronized void deleteCollection(UUID memoryId) {
        entries.values().removeIf(e -> memoryId.equals(e.memoryId()));
    }

    private void checkKey(AgentMemoryEntry entry) {
        if (entries.values().stream().anyMatch(e -> e.memoryId().equals(entry.memoryId())
                && e.key().equals(entry.key()) && !e.id().equals(entry.id()))) {
            throw new DuplicateKeyException("Memory key already exists");
        }
    }
}
