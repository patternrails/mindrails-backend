package com.mindrails.backend.memory.agent;

import static com.mindrails.backend.memory.agent.AgentMemoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.tool.spec.AgentMemoryTools;
import dev.langchain4j.invocation.InvocationContext;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class AgentMemoryToolsTest {
    private final Fixture f = new Fixture();
    private final UUID owner = f.agent(), sessionId = f.session(owner);
    private final AgentMemoryTools tools = tools(f, sessionId);
    private final InvocationContext context = context(f, sessionId);

    @Test
    void identicalCreatesAreIdempotentAtCapacityAndDifferentContentNeverOverwrites() {
        f.properties.setMaxEntries(1);
        var first = tools.create("response.language", AgentMemoryEntry.Type.PREFERENCE, "使用中文", List.of("语言", " ANSWER "), context);
        assertEquals("CREATED", first.status());
        var retry = tools.create("response.language", AgentMemoryEntry.Type.PREFERENCE, " 使用中文 ", List.of("answer", "语言"), context);
        assertEquals("UNCHANGED", retry.status());
        assertEquals(first.memories().getFirst().id(), retry.memories().getFirst().id());
        assertEquals(0, retry.memories().getFirst().version());
        assertError("CONFLICT", () -> tools.create("response.language", AgentMemoryEntry.Type.PREFERENCE, "使用英文", List.of(), context));
        assertEquals("使用中文", tools.get("response.language", context).memories().getFirst().content());
        assertError("CONFLICT", () -> tools.create("other", AgentMemoryEntry.Type.FACT, "Another", List.of(), context));
        assertEquals(1, success(f.memory.list(owner, 0, 20)).size());
    }

    @Test
    void updatePreservesManagementSettingsAndCapturesTheActualSourceSession() {
        var manual = success(f.memory.create(owner, new AgentMemoryInput("decision", AgentMemoryEntry.Type.DECISION,
                "Original", List.of("project"), true, 80, null, null)));
        var repeated = tools.create("decision", manual.type(), manual.content(), manual.tags(), context);
        assertEquals("UNCHANGED", repeated.status());
        assertNull(repeated.memories().getFirst().sourceSessionId());
        var changed = tools.update("decision", 0L, AgentMemoryEntry.Type.DECISION, "Revised", List.of("project"), context);
        assertEquals("UPDATED", changed.status());
        var saved = success(f.memory.get(owner, manual.id()));
        assertTrue(saved.pinned());
        assertEquals(80, saved.priority());
        assertEquals(sessionId, saved.sourceSessionId());
        assertEquals(1, saved.version());
        assertError("CONFLICT", () -> tools.update("decision", 0L, manual.type(), "Stale", List.of(), context));
        assertError("CONFLICT", () -> tools.delete("decision", 0L, context));
        assertEquals("DELETED", tools.delete("decision", 1L, context).status());
        assertError("NOT_FOUND", () -> tools.get("decision", context));
    }

    @Test
    void boundToolsCannotAccessAnotherAgentOrUseADeletedSession() {
        UUID other = f.agent(), otherSession = f.session(other);
        success(f.memory.create(other, input("private", "Other Agent secret", false, null)));
        assertError("NOT_FOUND", () -> tools.get("private", context));
        assertError("NOT_FOUND", () -> tools.update("private", 0L, AgentMemoryEntry.Type.FACT, "Wrong", List.of(), context));
        assertError("NOT_FOUND", () -> tools.delete("private", 0L, context));
        assertTrue(success(f.memory.list(owner, 0, 20)).isEmpty());
        assertEquals("Other Agent secret", success(f.memory.getByKey(other, "private")).content());
        success(f.agents.deleteSession(owner, sessionId));
        assertError("NOT_FOUND", () -> tools.create("fact", AgentMemoryEntry.Type.FACT, "Old Session", List.of(), context));
    }

    @Test
    void validatesMissingParameters() {
        assertError("INVALID_ARGUMENT", () -> tools.create("bad key", AgentMemoryEntry.Type.FACT, "value", List.of(), context));
        assertError("INVALID_ARGUMENT", () -> tools.create("fact", null, "value", List.of(), context));
        assertError("INVALID_ARGUMENT", () -> tools.update("fact", null, AgentMemoryEntry.Type.FACT, "value", List.of(), context));
        assertError("INVALID_ARGUMENT", () -> tools.delete("fact", -1L, context));
    }

    @Test
    void cancelledRunsCannotStartAgentMemoryOperations() {
        var cancellation = new java.util.concurrent.CompletableFuture<Void>();
        var invocation = InvocationContext.builder().invocationId(UUID.randomUUID())
                .invocationParameters(dev.langchain4j.invocation.InvocationParameters.from(com.mindrails.backend.agent.model.LlmClient.CANCELLATION, cancellation)).build();
        cancellation.complete(null);
        assertThrows(CancellationException.class,
                () -> tools.create("late", AgentMemoryEntry.Type.FACT, "Late", List.of(), invocation));
        assertTrue(success(f.memory.list(owner, 0, 20)).isEmpty());
    }

    @Test
    void oneSharedToolRoutesEachCallUsingItsOwnServerSuppliedIdentity() {
        UUID other = f.agent(), otherSession = f.session(other);
        var otherContext = context(f, otherSession);
        tools.create("database", AgentMemoryEntry.Type.FACT, "First Agent", List.of(), context);
        tools.create("database", AgentMemoryEntry.Type.FACT, "Other Agent", List.of(), otherContext);
        assertEquals("First Agent", tools.get("database", context).memories().getFirst().content());
        assertEquals("Other Agent", tools.get("database", otherContext).memories().getFirst().content());
        assertEquals(sessionId, success(f.memory.getByKey(owner, "database")).sourceSessionId());
        assertEquals(otherSession, success(f.memory.getByKey(other, "database")).sourceSessionId());
    }

    @Test
    void explicitSearchFindsRelevantFactsEvenWhenPinnedMemoriesFillTheAutomaticWindow() {
        for (int i = 0; i < 5; i++) success(f.memory.create(owner, input("pinned-" + i, "Always available " + i, true, null)));
        tools.create("database", AgentMemoryEntry.Type.DECISION, "MySQL", List.of("database"), context);
        assertEquals(5, success(f.memory.search(owner, "database")).size());
        var explicit = tools.search("database", context);
        assertEquals(1, explicit.memories().size());
        assertEquals("database", explicit.memories().getFirst().key());
    }

    static AgentMemoryTools tools(Fixture f, UUID sessionId) {
        return new AgentMemoryTools(f.memory);
    }
    static InvocationContext context(Fixture f, UUID sessionId) {
        return InvocationContext.builder().invocationId(UUID.randomUUID())
                .chatMemoryId(f.sessionRegistry.findManaged(sessionId).orElseThrow().getMemoryId())
                .invocationParameters(com.mindrails.backend.agent.model.LlmClient.parameters(f.sessionRegistry.findManaged(sessionId).orElseThrow(), null, "REST")).build();
    }
    static void assertError(String code, org.junit.jupiter.api.function.Executable action) {
        var failure = assertThrows(RuntimeException.class, action);
        var json = JsonMapper.builder().build().readTree(failure.getMessage());
        assertFalse(json.get("success").asBoolean());
        assertEquals(code, json.get("code").asText());
    }
}
