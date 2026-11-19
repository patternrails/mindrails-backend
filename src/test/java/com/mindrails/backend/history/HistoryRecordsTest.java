package com.mindrails.backend.history;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.model.KnowledgeSourceReference;
import com.mindrails.backend.history.service.internal.HistoryRecords;
import com.mindrails.backend.history.store.InMemoryHistoryStore;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.data.message.AiMessage;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.json.JsonMapper;

class HistoryRecordsTest {
    @Test
    void safeFailureDetailsSurviveArchivalAlongsidePartialContent() {
        UUID request = UUID.randomUUID();
        var parameters = LlmClient.parameters(Session.builder().id(UUID.randomUUID()).build(), request, "WEBSOCKET");
        var store = new InMemoryHistoryStore();
        var recorder = TestHistory.create(store, null);
        recorder.record(HistoryRecords.start(parameters, "Question"));
        recorder.record(HistoryRecords.partial(parameters, "answer", "Already received"));
        recorder.record(HistoryRecords.failed(parameters, "UNAVAILABLE", "Secure connection failed",
                "TLS_HANDSHAKE_FAILED", "SSLHandshakeException: Remote host terminated the handshake"));
        var record = store.find(parameters.get(LlmClient.RUN_ID)).orElseThrow();
        var metadata = new JsonMapper().readTree(record.metadata());
        assertEquals(HistoryRecord.Status.FAILED, record.status());
        assertEquals(request, record.requestId());
        assertEquals("Already received", metadata.path("partial").path("answer").asText());
        assertEquals("TLS_HANDSHAKE_FAILED", metadata.path("error").path("reason").asText());
        assertTrue(metadata.path("error").path("detail").asText().contains("SSLHandshakeException"));
    }

    @Test void terminalRecordRejectsLateMessagesAndFragments() {
        var parameters = LlmClient.parameters(Session.builder().id(UUID.randomUUID()).build(), null, "WEBSOCKET");
        var store = new InMemoryHistoryStore();
        var recorder = TestHistory.create(store, null);
        recorder.record(HistoryRecords.start(parameters, "Question"));
        recorder.record(HistoryRecords.partial(parameters, "answer", "Original partial reply"));
        recorder.record(HistoryRecords.finish(parameters, HistoryRecord.Status.CANCELLED, null, null, null, "RUN_CANCELLED", "Cancelled"));
        var first = store.find(parameters.get(LlmClient.RUN_ID)).orElseThrow();
        recorder.record(HistoryRecords.partial(parameters, "answer", "late delta"));
        var context = InvocationContext.builder().invocationParameters(parameters).build();
        recorder.record(HistoryRecords.message(context, AiMessage.from("late complete reply")));
        recorder.record(HistoryRecords.finish(parameters, HistoryRecord.Status.COMPLETED, null, null, null, null, null));
        assertSame(first, store.find(first.id()).orElseThrow());
        assertEquals("Original partial reply", JsonMapper.builder().build().readTree(first.metadata()).path("partial").path("answer").asString());
    }

    @Test void storageSerializesCompetingTerminalEntries() throws Exception {
        var parameters = LlmClient.parameters(Session.builder().id(UUID.randomUUID()).build(), null, "WEBSOCKET");
        var store = new InMemoryHistoryStore();
        var recorder = TestHistory.create(store, null);
        recorder.record(HistoryRecords.start(parameters, "Question"));
        var gate = new CountDownLatch(1);
        try (var workers = Executors.newFixedThreadPool(2)) {
            var completed = workers.submit(() -> { await(gate); recorder.record(HistoryRecords.finish(parameters, HistoryRecord.Status.COMPLETED, null, null, null, null, null)); });
            var cancelled = workers.submit(() -> { await(gate); recorder.record(HistoryRecords.finish(parameters, HistoryRecord.Status.CANCELLED, null, null, null, "RUN_CANCELLED", "Cancelled")); });
            gate.countDown(); completed.get(5, TimeUnit.SECONDS); cancelled.get(5, TimeUnit.SECONDS);
        }
        var archived = store.find(parameters.get(LlmClient.RUN_ID)).orElseThrow();
        assertTrue(List.of(HistoryRecord.Status.COMPLETED, HistoryRecord.Status.CANCELLED).contains(archived.status()));
        assertEquals(1, dev.langchain4j.data.message.ChatMessageDeserializer.messagesFromJson(archived.messages()).size());
    }

    @Test void knowledgeProvenanceKeepsJsonFieldNamesAndIds() {
        var parameters = LlmClient.parameters(Session.builder().id(UUID.randomUUID()).build(), null, "REST");
        var context = InvocationContext.builder().invocationParameters(parameters).build();
        UUID base = UUID.randomUUID(), document = UUID.randomUUID(), revision = UUID.randomUUID(), generation = UUID.randomUUID(), chunk = UUID.randomUUID();
        var entry = HistoryRecords.metadata(context, "knowledge", List.of(new KnowledgeSourceReference(base, document, revision, generation, chunk, 0.95)));
        var source = JsonMapper.builder().build().readTree(entry.metadata()).path("knowledge").get(0);
        assertEquals(base.toString(), source.path("knowledgeBaseId").asString());
        assertEquals(document.toString(), source.path("documentId").asString());
        assertEquals(revision.toString(), source.path("revisionId").asString());
        assertEquals(generation.toString(), source.path("indexGeneration").asString());
        assertEquals(chunk.toString(), source.path("chunkId").asString());
        assertEquals(0.95, source.path("score").asDouble());
    }
    private static void await(CountDownLatch gate) {
        try { assertTrue(gate.await(5, TimeUnit.SECONDS)); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new AssertionError(e); }
    }
}
