package com.mindrails.backend.memory.agent;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Immutable snapshots; all operations are scoped to a server-resolved memory collection. */
public interface AgentMemoryStore {
    default boolean available() { return true; }
    List<AgentMemoryEntry> entries(UUID memoryId);
    Optional<AgentMemoryEntry> find(UUID memoryId, UUID id);
    Optional<AgentMemoryEntry> findByKey(UUID memoryId, String key);
    void insert(AgentMemoryEntry entry);
    boolean update(AgentMemoryEntry entry, long expectedVersion);
    boolean delete(UUID memoryId, UUID id, long expectedVersion);
    void deleteCollection(UUID memoryId);
    record PreparedWrite(AgentMemoryEntry entry, Object state) {}
    /** Potentially remote embedding work, performed before acquiring the commit guard. */
    default PreparedWrite prepare(AgentMemoryEntry entry) { return new PreparedWrite(entry, null); }
    default void insertPrepared(PreparedWrite write) { insert(write.entry()); }
    default boolean updatePrepared(PreparedWrite write, long version) { return update(write.entry(), version); }
    default List<AgentMemoryEntry> retrieve(UUID agentId, UUID memoryId, String query, boolean automatic,
            AgentMemoryProperties properties) {
        var retrieval = new AgentMemoryRetrieval(properties);
        return automatic ? retrieval.retrieve(entries(memoryId), query) : retrieval.search(entries(memoryId), query);
    }
    /** Capture before deletion so a failed Agent delete can restore the collection. */
    default Runnable deletionRollback(UUID memoryId) {
        var backup = entries(memoryId);
        return () -> { deleteCollection(memoryId); backup.forEach(this::insert); };
    }
}
