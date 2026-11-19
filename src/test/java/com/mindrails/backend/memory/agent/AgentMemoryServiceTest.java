package com.mindrails.backend.memory.agent;

import com.mindrails.backend.agent.service.internal.TestSessionChat;
import static com.mindrails.backend.memory.agent.AgentMemoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.common.ServiceResult;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

@org.junit.jupiter.api.extension.ExtendWith(TestSessionChat.class)
class AgentMemoryServiceTest {
    @Test
    void crudEnforcesScopeKeysAndOptimisticVersions() {
        var f = new Fixture();
        UUID owner = f.agent(), other = f.agent();
        var first = success(f.memory.create(owner, input("database", "项目使用 MySQL", false, null)));
        assertEquals(owner, first.agentId());
        assertEquals(success(f.agents.get(owner)).getMemoryId(), first.memoryId());
        assertEquals(ServiceResult.ErrorCode.CONFLICT, failure(f.memory.create(owner, input("database", "duplicate", false, null))));
        success(f.memory.create(other, input("database", "Other Agent", false, null)));
        assertEquals(ServiceResult.ErrorCode.NOT_FOUND, failure(f.memory.get(other, first.id())));
        assertEquals(ServiceResult.ErrorCode.NOT_FOUND, failure(f.memory.update(other, first.id(), input("database", "bad", false, 0L))));
        assertEquals(ServiceResult.ErrorCode.NOT_FOUND, failure(f.memory.delete(other, first.id(), 0)));
        var updated = success(f.memory.update(owner, first.id(), input("database", "数据库已经迁移", false, 0L)));
        assertEquals(1, updated.version());
        assertEquals(first.createdAt(), updated.createdAt());
        assertEquals(ServiceResult.ErrorCode.CONFLICT, failure(f.memory.update(owner, first.id(), input("database", "stale", false, 0L))));
        assertEquals(ServiceResult.ErrorCode.CONFLICT, failure(f.memory.delete(owner, first.id(), 0)));
        success(f.memory.delete(owner, first.id(), 1));
        assertTrue(success(f.memory.list(owner, 0, 20)).isEmpty());
    }

    @Test
    void validatesOwnedProvenanceAndKeepsFactsAfterSessionDeletion() {
        var f = new Fixture();
        UUID owner = f.agent(), other = f.agent(), source = f.session(owner), foreign = f.session(other);
        var foreignInput = new AgentMemoryInput("fact", AgentMemoryEntry.Type.DECISION, "Shared decision", List.of(), false, 0, foreign, null);
        assertEquals(ServiceResult.ErrorCode.INVALID_ARGUMENT, failure(f.memory.create(owner, foreignInput)));
        var entry = success(f.memory.create(owner, new AgentMemoryInput("fact", AgentMemoryEntry.Type.DECISION,
                "Shared decision", List.of(" DECISION "), true, 10, source, null)));
        assertEquals(List.of("decision"), entry.tags());
        success(f.agents.deleteSession(owner, source));
        assertEquals(source, success(f.memory.get(owner, entry.id())).sourceSessionId());
        success(f.memory.update(owner, entry.id(), new AgentMemoryInput("fact", entry.type(), "Revised decision",
                entry.tags(), true, 10, source, 0L)));
    }

    @Test
    void pinnedAndLexicalRecallRespectCompleteEntryBudgetAndChineseQueries() {
        var f = new Fixture();
        UUID owner = f.agent();
        var pinned = success(f.memory.create(owner, input("language", "回答使用中文", true, null)));
        var relevant = success(f.memory.create(owner, input("database", "删除数据库会丢失记忆", false, null)));
        var fallback = success(f.memory.create(owner, input("cooking", "烹饪需要食材", false, null)));
        assertEquals(List.of(pinned, relevant, fallback), success(f.memory.search(owner, "如何删除数据库中的记忆？")));
        var noKeywords = success(f.memory.search(owner, "unrelated astronomy"));
        assertEquals(pinned, noKeywords.getFirst());
        assertTrue(noKeywords.contains(relevant));
        assertTrue(noKeywords.contains(fallback));
        assertTrue(success(f.memory.searchForTool(owner, "unrelated astronomy")).isEmpty());
        f.properties.getRetrieval().setMaxContextChars(512);
        var results = success(f.memory.search(owner, "数据库"));
        assertTrue(AgentMemoryRetrieval.PREFIX.length() + results.stream().mapToInt(e -> AgentMemoryRetrieval.format(e).length()).sum() <= 512);
        assertEquals(pinned, results.getFirst());
        f.properties.getRetrieval().setMaxResults(1);
        assertEquals(ServiceResult.ErrorCode.INVALID_ARGUMENT, failure(f.memory.create(owner, input("second-pin", "Must fit", true, null))));
    }

    @Test
    void rejectsOversizedContentCollectionAndPinnedContext() {
        var f = new Fixture();
        UUID owner = f.agent();
        f.properties.setMaxEntryChars(1000);
        assertEquals(ServiceResult.ErrorCode.INVALID_ARGUMENT, failure(f.memory.create(owner, input("oversized", "x".repeat(1001), false, null))));
        f.properties.getRetrieval().setMaxContextChars(512);
        assertEquals(ServiceResult.ErrorCode.INVALID_ARGUMENT, failure(f.memory.create(owner, input("pin", "x".repeat(500), true, null))));
        f.properties.setMaxEntries(1);
        success(f.memory.create(owner, input("one", "First", false, null)));
        assertEquals(ServiceResult.ErrorCode.CONFLICT, failure(f.memory.create(owner, input("two", "Second", false, null))));
        assertEquals(ServiceResult.ErrorCode.INVALID_ARGUMENT, failure(f.memory.list(owner, -1, 20)));
    }

    @Test
    void agentDeleteCleansItsSessionsAndMemory() {
        var f = new Fixture();
        UUID owner = f.agent(), session = f.session(owner);
        var entry = success(f.memory.create(owner, input("fact", "Retain on failure", true, null)));
        success(f.agents.delete(owner));
        assertEquals(ServiceResult.ErrorCode.NOT_FOUND, failure(f.memory.get(owner, entry.id())));
        assertTrue(f.sessionRegistry.findManaged(session).isEmpty());
    }

    @Test
    void deleteFailureRestoresEntriesAndEmptyAgentDeletesItsCollection() {
        var f = new Fixture();
        UUID owner = f.agent();
        var entry = success(f.memory.create(owner, input("fact", "Saved", true, null)));
        var agent = success(f.agents.get(owner));
        assertEquals(ServiceResult.ErrorCode.INTERNAL_ERROR, failure(f.memory.deleteWithAgent(agent,
                () -> ServiceResult.failure(ServiceResult.ErrorCode.INTERNAL_ERROR, "Injected failure"))));
        assertEquals(entry, success(f.memory.get(owner, entry.id())));
        success(f.agents.delete(owner));
        assertEquals(ServiceResult.ErrorCode.NOT_FOUND, failure(f.memory.list(owner, 0, 20)));
    }

}
