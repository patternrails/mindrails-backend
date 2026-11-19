package com.mindrails.backend.memory.agent;

import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;

@Slf4j
public final class AgentMemoryService {
    private final AgentRegistry agents;
    private final SessionRegistry sessions;
    private final AgentMemoryStore store;
    private final AgentMemoryProperties properties;
    private final AgentMemoryRetrieval retrieval;
    // Bounded lock storage; only short memory operations/deletion hold these, never model execution.
    private final ReentrantLock[] locks = new ReentrantLock[256];

    public AgentMemoryService(AgentRegistry agents, SessionRegistry sessions, AgentMemoryStore store,
            AgentMemoryProperties properties) {
        this.agents = agents;
        this.sessions = sessions;
        this.store = store;
        this.properties = properties;
        this.retrieval = new AgentMemoryRetrieval(properties);
        java.util.Arrays.setAll(locks, ignored -> new ReentrantLock());
    }

    public record SavedMemory(AgentMemoryEntry entry, boolean created) {}

    public boolean available() {
        return store.available();
    }

    public ServiceResult<AgentMemoryEntry> create(UUID agentId, AgentMemoryInput input) {
        return create(agentId, input, false).map(SavedMemory::entry);
    }

    /** A repeated model create never overwrites a different fact or changes its management settings. */
    public ServiceResult<SavedMemory> createOrGet(UUID agentId, AgentMemoryInput input) {
        return create(agentId, input, true);
    }

    private ServiceResult<SavedMemory> create(UUID agentId, AgentMemoryInput input, boolean reuseIdentical) {
        ServiceResult<SavedMemory> candidate = withAgent(agentId, agent -> {
            String error = validate(agent, input, null);
            if (error != null) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, error);
            if (reuseIdentical) {
                var existing = store.findByKey(agent.getMemoryId(), input.key()).orElse(null);
                if (existing != null) {
                    boolean same = existing.type() == input.type() && existing.content().equals(input.content().strip())
                            && java.util.Set.copyOf(existing.tags()).equals(java.util.Set.copyOf(tags(input)));
                    return same ? ServiceResult.success(new SavedMemory(existing, false))
                            : ServiceResult.failure(ErrorCode.CONFLICT, "Memory key already exists with different content; read it and update with its version");
                }
            }
            var entries = store.entries(agent.getMemoryId());
            if (entries.size() >= properties.getMaxEntries()) {
                return ServiceResult.failure(ErrorCode.CONFLICT, "Agent memory collection is full");
            }
            Instant now = Instant.now();
            var entry = entry(agent, UUID.randomUUID(), input, now, now, 0);
            error = validatePinned(entries, entry);
            if (error != null) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, error);
            return ServiceResult.success(new SavedMemory(entry, true));
        });
        if (candidate instanceof ServiceResult.Failure<SavedMemory>) return candidate;
        var saved = ((ServiceResult.Success<SavedMemory>) candidate).value();
        if (!saved.created()) return candidate;
        return prepareAndCommit(saved.entry(), prepared -> withAgent(agentId, agent -> {
            String error = validate(agent, input, null);
            if (error != null) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, error);
            if (!agent.getMemoryId().equals(saved.entry().memoryId()))
                return ServiceResult.failure(ErrorCode.CONFLICT, "Agent memory collection changed");
            var existing = store.findByKey(agent.getMemoryId(), input.key()).orElse(null);
            if (existing != null) {
                boolean identical = reuseIdentical && existing.type() == saved.entry().type()
                        && existing.content().equals(saved.entry().content())
                        && java.util.Set.copyOf(existing.tags()).equals(java.util.Set.copyOf(saved.entry().tags()));
                return identical ? ServiceResult.success(new SavedMemory(existing, false))
                        : ServiceResult.failure(ErrorCode.CONFLICT, "Memory key already exists with different content");
            }
            var entries = store.entries(agent.getMemoryId());
            if (entries.size() >= properties.getMaxEntries()) return ServiceResult.failure(ErrorCode.CONFLICT, "Agent memory collection is full");
            error = validatePinned(entries, saved.entry());
            if (error != null) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, error);
            store.insertPrepared(prepared);
            return ServiceResult.success(saved);
        }));
    }

    public ServiceResult<AgentMemoryEntry> getByKey(UUID agentId, String key) {
        if (!validKey(key)) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Invalid memory key");
        return withAgent(agentId, agent -> store.findByKey(agent.getMemoryId(), key)
                .<ServiceResult<AgentMemoryEntry>>map(ServiceResult::success)
                .orElseGet(() -> ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent memory entry not found")));
    }

    /** Snapshot management settings, prepare outside the guard, then recheck the expected version. */
    public ServiceResult<AgentMemoryEntry> updateByKey(UUID agentId, String key, Long expectedVersion,
            AgentMemoryEntry.Type type, String content, List<String> tags, UUID sourceSessionId) {
        if (!validKey(key) || expectedVersion == null || expectedVersion < 0) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Valid key and expectedVersion are required");
        }
        var found = getByKey(agentId, key);
        if (found instanceof ServiceResult.Failure<AgentMemoryEntry>) return found;
        var current = ((ServiceResult.Success<AgentMemoryEntry>) found).value();
        return update(agentId, current.id(), new AgentMemoryInput(key, type, content, tags, current.pinned(),
                current.priority(), sourceSessionId, expectedVersion));
    }

    public ServiceResult<Void> deleteByKey(UUID agentId, String key, Long expectedVersion) {
        if (!validKey(key) || expectedVersion == null || expectedVersion < 0) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Valid key and expectedVersion are required");
        }
        return withAgent(agentId, agent -> {
            var current = store.findByKey(agent.getMemoryId(), key).orElse(null);
            if (current == null) return ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent memory entry not found");
            return delete(agentId, current.id(), expectedVersion);
        });
    }

    /** Scope comes from the cached client's server-side binding and the hidden LC4J invocation context. */
    @org.springframework.beans.factory.annotation.Autowired(required=false)
    private com.mindrails.backend.agent.registry.TaskRegistry taskRegistry;

    public <T> ServiceResult<T> forToolTask(UUID taskId, UUID agentId, Supplier<ServiceResult<T>> action) {
        if (taskRegistry == null || taskRegistry.find(taskId).filter(t -> java.util.Objects.equals(agentId,t.getParentAgentId())).isEmpty())
            return ServiceResult.failure(ErrorCode.NOT_FOUND, "Memory tool Task scope no longer exists");
        return action.get();
    }
    public List<AgentMemoryEntry> forTask(UUID taskId, UUID agentId, String query) {
        var result=forToolTask(taskId,agentId,() -> retrieve(agentId,query,true));
        return switch(result) {
            case ServiceResult.Success<List<AgentMemoryEntry>> success -> success.value();
            case ServiceResult.Failure<List<AgentMemoryEntry>> failure -> throw new AgentMemoryAccessException(failure.code(),failure.message());
        };
    }

    public <T> ServiceResult<T> forToolSession(UUID sessionId, UUID agentId,
            Supplier<ServiceResult<T>> action) {
        try {
            var session = sessions.findManaged(sessionId).orElse(null);
            if (session == null || !java.util.Objects.equals(session.getParentAgentId(), agentId)) {
                return ServiceResult.failure(ErrorCode.NOT_FOUND, "Memory tool Session scope no longer exists");
            }
            return action.get();
        } catch (RuntimeException failure) {
            log.error("Agent memory tool scope validation failed: sessionId={}", sessionId, failure);
            return ServiceResult.failure(ErrorCode.INTERNAL_ERROR, "Agent memory storage is unavailable");
        }
    }

    public ServiceResult<AgentMemoryEntry> update(UUID agentId, UUID entryId, AgentMemoryInput input) {
        ServiceResult<AgentMemoryEntry> candidate = withAgent(agentId, agent -> {
            var current = entryId == null ? null : store.find(agent.getMemoryId(), entryId).orElse(null);
            if (current == null) return ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent memory entry not found");
            String error = validate(agent, input, current);
            if (error != null) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, error);
            if (input.version() != current.version()) {
                return ServiceResult.failure(ErrorCode.CONFLICT, "Agent memory version changed");
            }
            var updated = entry(agent, current.id(), input, current.createdAt(), Instant.now(), current.version() + 1);
            error = validatePinned(store.entries(agent.getMemoryId()), updated);
            if (error != null) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, error);
            return ServiceResult.success(updated);
        });
        if (candidate instanceof ServiceResult.Failure<AgentMemoryEntry>) return candidate;
        var updated = ((ServiceResult.Success<AgentMemoryEntry>) candidate).value();
        return prepareAndCommit(updated, prepared -> withAgent(agentId, agent -> {
            if (!agent.getMemoryId().equals(updated.memoryId())) return ServiceResult.failure(ErrorCode.CONFLICT, "Agent memory collection changed");
            var current = store.find(agent.getMemoryId(), entryId).orElse(null);
            if (current == null) return ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent memory entry not found");
            if (current.version() != input.version()) return ServiceResult.failure(ErrorCode.CONFLICT, "Agent memory version changed");
            String error = validate(agent, input, current);
            if (error == null) error = validatePinned(store.entries(agent.getMemoryId()), updated);
            if (error != null) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, error);
            return store.updatePrepared(prepared, current.version()) ? ServiceResult.success(updated)
                    : ServiceResult.failure(ErrorCode.CONFLICT, "Agent memory version changed");
        }));
    }

    public ServiceResult<AgentMemoryEntry> get(UUID agentId, UUID entryId) {
        return withAgent(agentId, agent -> entryId == null
                ? ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Entry ID is required")
                : store.find(agent.getMemoryId(), entryId).<ServiceResult<AgentMemoryEntry>>map(ServiceResult::success)
                        .orElseGet(() -> ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent memory entry not found")));
    }

    public ServiceResult<List<AgentMemoryEntry>> list(UUID agentId, int page, int size) {
        if (page < 0 || size < 1 || size > 100) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Invalid page or size");
        return withAgent(agentId, agent -> ServiceResult.success(store.entries(agent.getMemoryId()).stream()
                .skip((long) page * size).limit(size).toList()));
    }

    public ServiceResult<Void> delete(UUID agentId, UUID entryId, long version) {
        if (entryId == null || version < 0) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Entry ID and non-negative version are required");
        return withAgent(agentId, agent -> {
            if (store.find(agent.getMemoryId(), entryId).isEmpty()) {
                return ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent memory entry not found");
            }
            return store.delete(agent.getMemoryId(), entryId, version) ? ServiceResult.success()
                    : ServiceResult.failure(ErrorCode.CONFLICT, "Agent memory version changed");
        });
    }

    public ServiceResult<List<AgentMemoryEntry>> search(UUID agentId, String query) {
        return search(agentId, query, true);
    }

    public ServiceResult<List<AgentMemoryEntry>> searchForTool(UUID agentId, String query) {
        return search(agentId, query, false);
    }

    private ServiceResult<List<AgentMemoryEntry>> search(UUID agentId, String query, boolean includePinned) {
        if (query == null || query.isBlank() || query.length() > 10000) {
            return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Query must contain 1 to 10000 characters");
        }
        return retrieve(agentId, query, includePinned);
    }

    /** Called once per chat invocation, outside the model execution and without caching entry contents. */
    public List<AgentMemoryEntry> forSession(UUID sessionId, UUID agentId, String query) {
        var session = sessions.findManaged(sessionId).orElseThrow(() -> new AgentMemoryAccessException(ErrorCode.NOT_FOUND, "Session no longer exists"));
        if (!java.util.Objects.equals(agentId, session.getParentAgentId())) {
            throw new AgentMemoryAccessException(ErrorCode.CONFLICT, "Session's Agent changed");
        }
        var result = retrieve(agentId, query, true);
        return switch (result) {
            case ServiceResult.Success<List<AgentMemoryEntry>> success -> success.value();
            case ServiceResult.Failure<List<AgentMemoryEntry>> failure -> throw new AgentMemoryAccessException(failure.code(), failure.message());
        };
    }

    /** Agent deletion and memory writes share this guard. Restore the collection if Agent deletion fails. */
    public ServiceResult<Void> deleteWithAgent(Agent agent, Supplier<ServiceResult<Void>> deletion) {
        var lock = lock(agent.getId());
        lock.lock();
        try {
            if (!available()) return deletion.get();
            Runnable rollback = store.deletionRollback(agent.getMemoryId());
            try {
                store.deleteCollection(agent.getMemoryId());
                var result = deletion.get();
                if (result instanceof ServiceResult.Failure<?>) rollback.run();
                return result;
            } catch (RuntimeException | Error failure) {
                try { rollback.run(); } catch (RuntimeException restoreFailure) { failure.addSuppressed(restoreFailure); }
                throw failure;
            }
        } finally { lock.unlock(); }
    }

    private <T> ServiceResult<T> prepareAndCommit(AgentMemoryEntry entry,
            Function<AgentMemoryStore.PreparedWrite, ServiceResult<T>> commit) {
        try {
            var prepared = store.prepare(entry);
            com.mindrails.backend.embedding.JsonHttpClient.checkActive();
            return commit.apply(prepared);
        } catch (RuntimeException failure) {
            log.error("Agent memory preparation failed: agentId={}", entry.agentId(), failure);
            return ServiceResult.failure(ErrorCode.INTERNAL_ERROR, "Agent memory preparation or storage is unavailable");
        }
    }

    private ServiceResult<List<AgentMemoryEntry>> retrieve(UUID agentId, String query, boolean automatic) {
        var scope = withAgent(agentId, agent -> ServiceResult.success(agent.getMemoryId()));
        if (scope instanceof ServiceResult.Failure<UUID> failure) return ServiceResult.failure(failure.code(), failure.message());
        UUID memoryId = ((ServiceResult.Success<UUID>) scope).value();
        try {
            var entries = store.retrieve(agentId, memoryId, query, automatic, properties);
            return withAgent(agentId, agent -> {
                if (!agent.getMemoryId().equals(memoryId)) return ServiceResult.failure(ErrorCode.CONFLICT, "Agent memory collection changed");
                // Recheck after remote retrieval: removed or superseded entries must never enter a request.
                var current = store.entries(memoryId).stream().collect(java.util.stream.Collectors.toMap(AgentMemoryEntry::id, e -> e));
                return ServiceResult.success(entries.stream().filter(entry -> entry.agentId().equals(agentId)
                        && entry.memoryId().equals(memoryId) && entry.equals(current.get(entry.id()))).toList());
            });
        } catch (RuntimeException failure) {
            log.error("Agent memory retrieval failed: agentId={}", agentId, failure);
            return ServiceResult.failure(ErrorCode.INTERNAL_ERROR, "Agent memory retrieval is unavailable");
        }
    }

    private <T> ServiceResult<T> withAgent(UUID agentId, Function<Agent, ServiceResult<T>> action) {
        if (agentId == null) return ServiceResult.failure(ErrorCode.INVALID_ARGUMENT, "Agent ID is required");
        var lock = lock(agentId);
        lock.lock();
        try {
            var agent = agents.findManaged(agentId).orElse(null);
            if (agent == null) return ServiceResult.failure(ErrorCode.NOT_FOUND, "Agent not found");
            if (agent.getMemoryId() == null) return ServiceResult.failure(ErrorCode.CONFLICT, "Agent has no memory collection ID");
            if (!available()) return ServiceResult.failure(ErrorCode.UNAVAILABLE, "Configure a default embedding provider to use Agent memory");
            return action.apply(agent);
        } catch (DuplicateKeyException duplicate) {
            return ServiceResult.failure(ErrorCode.CONFLICT, "Agent memory key already exists");
        } catch (RuntimeException failure) {
            log.error("Agent memory operation failed: agentId={}", agentId, failure);
            return ServiceResult.failure(ErrorCode.INTERNAL_ERROR, "Agent memory storage is unavailable");
        } finally { lock.unlock(); }
    }

    private ReentrantLock lock(UUID agentId) { return locks[Math.floorMod(agentId.hashCode(), locks.length)]; }

    private String validate(Agent agent, AgentMemoryInput input, AgentMemoryEntry current) {
        if (input == null || !validKey(input.key())) return "Invalid memory key";
        if (input.type() == null || input.content() == null || input.content().isBlank()
                || input.content().length() > properties.getMaxEntryChars()) return "Invalid memory type or content length";
        if (input.priority() < 0 || input.priority() > 100) return "Priority must be between 0 and 100";
        if (input.tags() != null && (input.tags().size() > 20 || input.tags().stream()
                .anyMatch(tag -> tag == null || tag.isBlank() || tag.length() > 64))) return "Invalid memory tags";
        if (current == null && input.version() != null && input.version() != 0) return "New memory version must be zero";
        if (current != null && (input.version() == null || input.version() < 0)) return "Version is required for updates";
        if (input.sourceSessionId() != null && (current == null || !input.sourceSessionId().equals(current.sourceSessionId()))) {
            var source = sessions.findManaged(input.sourceSessionId()).orElse(null);
            if (source == null || !agent.getId().equals(source.getParentAgentId())) return "Source Session must belong to this Agent";
        }
        return null;
    }

    private String validatePinned(List<AgentMemoryEntry> current, AgentMemoryEntry entry) {
        var proposed = new ArrayList<>(current.stream().filter(e -> !e.id().equals(entry.id())).toList());
        proposed.add(entry);
        if (proposed.stream().filter(AgentMemoryEntry::pinned).count() > properties.getRetrieval().getMaxResults()
                || retrieval.pinnedChars(proposed) > properties.getRetrieval().getMaxContextChars()) {
            return "Pinned memories exceed the retrieval count or context budget";
        }
        return null;
    }

    private AgentMemoryEntry entry(Agent agent, UUID id, AgentMemoryInput input, Instant createdAt, Instant updatedAt, long version) {
        return new AgentMemoryEntry(id, agent.getId(), agent.getMemoryId(), input.key(), input.type(), input.content().strip(),
                tags(input), input.pinned(), input.priority(), input.sourceSessionId(), createdAt, updatedAt, version);
    }

    private static List<String> tags(AgentMemoryInput input) {
        return input.tags() == null ? List.of() : input.tags().stream()
                .map(tag -> tag.strip().toLowerCase(Locale.ROOT)).distinct().toList();
    }

    private static boolean validKey(String key) { return key != null && key.matches("[a-z0-9][a-z0-9._-]{0,127}"); }
}
