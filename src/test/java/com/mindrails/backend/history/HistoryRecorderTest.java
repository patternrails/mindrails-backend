package com.mindrails.backend.history;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.service.internal.HistoryRecords;
import com.mindrails.backend.history.store.InMemoryHistoryStore;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class HistoryRecorderTest {
    @Test void storageFailureNeverEscapesTheRecordingBoundary() {
        var recorder = TestHistory.create(ignored -> { throw new IllegalStateException("unavailable"); }, null);
        var parameters = LlmClient.parameters(Session.builder().id(UUID.randomUUID()).memoryId(UUID.randomUUID()).build(), null, "REST");
        var record = HistoryRecords.start(parameters, "Hello");
        assertDoesNotThrow(() -> recorder.record(record));
        assertDoesNotThrow(() -> recorder.record(null));
    }

    @Test void archiveKeepsIdentityCapturedAtStartEvenIfResourcesChange() {
        var agents = mock(AgentRegistry.class);
        UUID agentId = UUID.randomUUID(), ownerId = UUID.randomUUID(), memoryId = UUID.randomUUID();
        var agent = Agent.builder().id(agentId).ownerId(ownerId).build();
        when(agents.findManaged(agentId)).thenReturn(Optional.of(agent));
        var session = Session.builder().id(UUID.randomUUID()).parentAgentId(agentId).memoryId(memoryId).build();
        var parameters = LlmClient.parameters(session, null, "REST");
        var store = new InMemoryHistoryStore();
        var recorder = TestHistory.create(store, agents);
        recorder.record(HistoryRecords.start(parameters, "Question"));
        agent.setOwnerId(UUID.randomUUID());
        session.setMemoryId(UUID.randomUUID());
        session.setParentAgentId(UUID.randomUUID());
        recorder.record(HistoryRecords.finish(parameters, HistoryRecord.Status.COMPLETED, null, null, null, null, null));
        var archived = store.find(parameters.get(LlmClient.RUN_ID)).orElseThrow();
        assertEquals(ownerId, archived.ownerId());
        assertEquals(agentId, archived.agentId());
        assertEquals(memoryId, archived.memoryId());
    }
}
