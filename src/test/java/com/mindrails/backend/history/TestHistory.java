package com.mindrails.backend.history;

import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.history.store.HistoryStore;

public final class TestHistory {
    private TestHistory() {}

    public static ChatHistory create(HistoryStore store, AgentRegistry agents) {
        return new ChatHistory(store, agents);
    }

    /** Observe completed archives while exercising the same immutable merge as the real stores. */
    public static HistoryStore collect(java.util.List<com.mindrails.backend.history.model.HistoryRecord> records) {
        var store = new com.mindrails.backend.history.store.InMemoryHistoryStore();
        return entry -> {
            synchronized (records) {
                store.insert(entry);
                var archived = store.find(entry.id()).orElseThrow();
                if (archived.status() != com.mindrails.backend.history.model.HistoryRecord.Status.RUNNING
                        && records.stream().noneMatch(record -> record.id().equals(entry.id()))) records.add(archived);
            }
        };
    }

    public static HistoryStore completed(HistoryStore sink) {
        var store = new com.mindrails.backend.history.store.InMemoryHistoryStore();
        return entry -> {
            store.insert(entry);
            if (entry.status() != com.mindrails.backend.history.model.HistoryRecord.Status.RUNNING) {
                sink.insert(store.find(entry.id()).orElseThrow());
            }
        };
    }

    public static ChatHistory disabled() {
        return create(ignored -> {}, null);
    }
}
