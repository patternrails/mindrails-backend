package com.mindrails.backend.observability;
import com.mindrails.backend.history.model.HistorySummary;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.model.HistoryFilter;
import com.mindrails.backend.history.store.MysqlHistoryStore;
import com.mindrails.backend.history.store.MysqlHistoryQueryStore;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.history.model.*;
import com.mindrails.backend.history.store.*;
import com.mindrails.backend.history.service.*;
import com.mindrails.backend.history.config.*;
import com.mindrails.backend.metrics.*;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;

class MysqlObservabilityIntegrationTest {
    final HikariDataSource source=new HikariDataSource();
    final JdbcTemplate jdbc;
    final UUID agent=UUID.randomUUID(),session=UUID.randomUUID();
    MysqlObservabilityIntegrationTest(){
        source.setJdbcUrl(System.getenv().getOrDefault("MINDRAILS_MYSQL_URL","jdbc:mysql://127.0.0.1:3306/mindrails"));source.setUsername(System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME","mindrails_app"));source.setPassword(System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        source.addDataSourceProperty("connectionTimeZone","UTC");source.addDataSourceProperty("forceConnectionTimeZoneToSession","true");jdbc=new JdbcTemplate(source);
    }
    @AfterEach void cleanup(){try{
        jdbc.update("DELETE c FROM metrics_model_calls c JOIN metrics_runs r ON c.run_id=r.run_id WHERE r.agent_id=?",agent.toString());
        jdbc.update("DELETE c FROM metrics_capability_calls c JOIN metrics_runs r ON c.run_id=r.run_id WHERE r.agent_id=?",agent.toString());
        jdbc.update("DELETE FROM metrics_runs WHERE agent_id=?",agent.toString());jdbc.update("DELETE FROM history WHERE agent_id=?",agent.toString());
    }finally{source.close();}}
    @Test void readsUsageAggregatesAndNeverCountsUnknownAsZero(){
        var p=new MetricsProperties();
        try(var recorder=new TestMetrics(p,new MysqlMetricsRepository(jdbc, new MetricsProperties(), new MetricsHealth()),new MetricsHealth(),false)){
            var run=recorder.begin(Session.builder().id(session).parentAgentId(agent).memoryId(UUID.randomUUID()).build(),null,"REST");
            run.modelStarted(UUID.randomUUID(),"fake").response(ChatResponse.builder().aiMessage(AiMessage.from("a")).tokenUsage(new TokenUsage(4,5,9)).build());
            run.modelStarted(UUID.randomUUID(),"fake").failed();run.finish("FAILED",null,null);recorder.flush();
            var query=new MysqlMetricsQueryStore(jdbc);var result=query.overview(new ArchiveFilter(Instant.now().minusSeconds(60),Instant.now().plusSeconds(60),agent,null,null));
            assertEquals(1,result.summary().runs());assertEquals(2,result.summary().modelCalls());assertEquals(9L,result.summary().totalTokens());assertEquals(1,result.summary().usageKnownCalls());assertEquals(1,result.trend().size());assertEquals(1,result.models().size());
            assertEquals(0,query.overview(new ArchiveFilter(null,null,UUID.randomUUID(),null,null)).summary().runs());
        }
    }
    @Test void listsOnlyScalarsAndLazilyReadsArchiveJson(){
        var record=new HistoryRecord(UUID.randomUUID(),null,agent,session,UUID.randomUUID(),null,null,null,HistoryRecord.Transport.STREAM,HistoryRecord.Status.CANCELLED,
                ChatMessageSerializer.messagesToJson(List.of(UserMessage.from("hello"),AiMessage.from("world"))),"{}",Instant.now(),Instant.now(),1);
        new MysqlHistoryStore(jdbc).insert(record);var query=new MysqlHistoryQueryStore(jdbc);
        var page=query.list(new HistoryFilter(null,null,agent,null,null),0,20);
        assertEquals(1,page.total());assertNull(page.items().getFirst().totalTokens());assertEquals("{}", query.metadata(record.id()).orElseThrow());assertEquals("USER",tools.jackson.databind.json.JsonMapper.builder().build().readTree(query.originalMessage(record.id(),0).orElseThrow()).path("type").asString());assertTrue(query.originalMessage(record.id(),2).isEmpty());assertEquals(tools.jackson.databind.json.JsonMapper.builder().build().readTree(record.messages()), tools.jackson.databind.json.JsonMapper.builder().build().readTree(query.find(record.id()).orElseThrow().messages()));
        assertTrue(query.delete(record.id()));assertFalse(query.delete(record.id()));assertTrue(query.find(record.id()).isEmpty());
    }
    @Test void sessionMetricsReadAllRetainedRunsWithoutDateOrStatusLimits() {
        var repository = new MysqlMetricsRepository(jdbc, new MetricsProperties(), new MetricsHealth());
        var old = Instant.now().minus(java.time.Duration.ofDays(120));
        var runId = UUID.randomUUID();
        var saved = com.mindrails.backend.metrics.MetricsSnapshot.builder()
                .runId(runId).instanceId(UUID.randomUUID()).agentId(agent).sessionId(session).memoryId(UUID.randomUUID())
                .transport("REST").status("SUCCEEDED").startedAt(old).endedAt(old).lastObservedAt(old)
                .modelCalls(List.of(com.mindrails.backend.metrics.MetricsSnapshot.ModelCall.builder()
                        .modelCallId(UUID.randomUUID()).runId(runId).invocationId(UUID.randomUUID())
                        .status("SUCCEEDED").startedAt(old).endedAt(old).inputTokens(10L).outputTokens(4L).totalTokens(14L).build())).build();
        repository.save(saved);
        // Saving the same absolute snapshot again must not count consumption twice.
        repository.save(saved);
        var p = new MetricsProperties();
        try (var recorder = new TestMetrics(p, repository, new MetricsHealth(), false)) {
            for (var target : List.of(session, UUID.randomUUID())) {
                var run = recorder.begin(Session.builder().id(target).parentAgentId(agent).memoryId(UUID.randomUUID()).build(), null, "REST");
                run.modelStarted(UUID.randomUUID(), "fake").response(ChatResponse.builder().aiMessage(AiMessage.from("a")).tokenUsage(new TokenUsage(2, 3, 5)).build());
                run.modelStarted(UUID.randomUUID(), "fake").failed();
                run.finish("FAILED", null, null);
            }
            recorder.flush();
        }
        var query = new MysqlMetricsQueryStore(jdbc);
        var result = query.session(session);
        assertEquals(2, result.summary().runs());
        assertEquals(3, result.summary().modelCalls());
        assertEquals(12L, result.summary().inputTokens());
        assertEquals(7L, result.summary().outputTokens());
        assertEquals(19L, result.summary().totalTokens());
        assertEquals(2, result.summary().usageKnownCalls());
        assertNotNull(result.lastPersistedAt());
        assertEquals(0L, query.session(UUID.randomUUID()).summary().totalTokens());
    }
    @Test void largeJsonDoesNotEnterSortBufferAndSummaryOrderMatchesThePage() {
        source.setConnectionInitSql("SET SESSION sort_buffer_size=32768");
        assertEquals(32768L, jdbc.queryForObject("SELECT @@session.sort_buffer_size", Long.class));
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var expected = new HashMap<UUID, HistoryRecord>();
        var memory = new InMemoryHistoryStore();
        var started = Instant.parse("2026-10-17T00:00:00Z");
        for (int i = 0; i < 12; i++) {
            var record = new HistoryRecord(UUID.randomUUID(), null, agent, session, UUID.randomUUID(), null, null, null,
                    HistoryRecord.Transport.STREAM, i % 2 == 0 ? HistoryRecord.Status.COMPLETED : HistoryRecord.Status.CANCELLED,
                    "[{\"type\":\"FUTURE_MESSAGE\",\"payload\":\"" + "x".repeat(300_000) + "\"}]",
                    "{\"row\":" + i + ",\"usage\":{\"totalTokens\":" + i + "},\"finishReason\":\"STOP😀\",\"partial\":{\"answer\":\"" + "😀".repeat(40_000) + "\"}}",
                    started.plusSeconds(i), started.plusSeconds(i + 1), 1);
            // Seed raw archived JSON directly, including a message type unknown to this runtime.
            jdbc.update("INSERT INTO history (id,agent_id,session_id,memory_id,transport,status,messages,metadata,started_at,finished_at,schema_version) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    record.id().toString(), agent.toString(), session.toString(), record.memoryId().toString(),
                    record.transport().name(), record.status().name(), record.messages(), record.metadata(),
                    java.sql.Timestamp.from(record.startedAt()), java.sql.Timestamp.from(record.finishedAt()), 1);
            expected.put(record.id(), record); memory.insert(record);
        }
        var query = new MysqlHistoryQueryStore(jdbc);
        var filter = new HistoryFilter(null, null, agent, session, null);
        var page = query.list(filter, 1, 5);
        assertEquals(12, page.total()); assertEquals(5, page.items().size()); assertTrue(page.hasNext());
        assertEquals(memory.list(filter, 1, 5).items().stream().map(HistorySummary::id).toList(), page.items().stream().map(HistorySummary::id).toList());
        for (var item : page.items()) {
            assertEquals(HistorySummary.from(expected.get(item.id())), item);
            assertEquals(json.readTree(expected.get(item.id()).metadata()), json.readTree(query.metadata(item.id()).orElseThrow()));
            assertEquals(json.readTree(expected.get(item.id()).messages()), json.readTree(query.find(item.id()).orElseThrow().messages()));
        }
        assertEquals(6, query.list(new HistoryFilter(null,null,agent,session,"CANCELLED"),0,20).total());
        assertTrue(query.list(filter, 10, 20).items().isEmpty());
    }
}
