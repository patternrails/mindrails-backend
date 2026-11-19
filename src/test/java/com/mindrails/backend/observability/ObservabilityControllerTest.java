package com.mindrails.backend.observability;
import com.mindrails.backend.history.api.web.HistoryErrorAdvice;
import com.mindrails.backend.history.api.web.HistoryController;
import com.mindrails.backend.history.api.web.*;
import com.mindrails.backend.history.service.DefaultHistoryService;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.history.model.HistoryFilter;
import com.mindrails.backend.history.store.InMemoryHistoryStore;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.history.model.*;
import com.mindrails.backend.history.store.*;
import com.mindrails.backend.history.service.*;
import com.mindrails.backend.history.config.*;
import com.mindrails.backend.metrics.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.time.*;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

class ObservabilityControllerTest {
    final InMemoryHistoryStore history=new InMemoryHistoryStore();
    final InMemoryMetricsRepository metrics=new InMemoryMetricsRepository(new MetricsProperties(), new MetricsHealth());
    final MetricsProperties properties=new MetricsProperties();
    final TestMetrics recorder;
    final MockMvc mvc;
    final UUID agent=UUID.randomUUID(),session=UUID.randomUUID();
    ObservabilityControllerTest() {
        recorder=new TestMetrics(properties,metrics,new MetricsHealth(),false);
        mvc=MockMvcBuilders.standaloneSetup(new ObservabilityController(filter->MetricsAggregates.from(metrics.snapshots(),filter), properties, recorder.health(), recorder.buffer()), new HistoryController(new DefaultHistoryService(history))).setControllerAdvice(new HistoryErrorAdvice()).build();
    }
    @AfterEach void close(){recorder.close();}
    HistoryRecord record(UUID id,String input,HistoryRecord.Status status){
        return new HistoryRecord(id,null,agent,session,UUID.randomUUID(),null,null,null,HistoryRecord.Transport.SYNC,status,
                ChatMessageSerializer.messagesToJson(List.of(UserMessage.from(input),AiMessage.from("answer"))),"{}",Instant.now(),Instant.now(),1);
    }
    @Test void historySummaryDetailDeleteAndMissing() throws Exception {
        var record=record(UUID.randomUUID(),"secret ".repeat(50),HistoryRecord.Status.COMPLETED);history.insert(record);
        mvc.perform(get("/api/history")).andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(1))
                .andExpect(jsonPath("$.items[0].metadata").doesNotExist())
                .andExpect(jsonPath("$.items[0].preview").doesNotExist())
                .andExpect(jsonPath("$.items[0].responsePreview").doesNotExist())
                .andExpect(jsonPath("$.items[0].messages").doesNotExist());
        mvc.perform(get("/api/history/"+record.id())).andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(record.id().toString()))
                .andExpect(jsonPath("$.messages[0].type").value("USER"))
                .andExpect(jsonPath("$.messages[0].contents[0].text").value("secret ".repeat(50)))
                .andExpect(jsonPath("$.messages[1].text").value("answer"))
                .andExpect(jsonPath("$.metadata").isMap());
        mvc.perform(delete("/api/history/"+record.id())).andExpect(status().isNoContent());
        mvc.perform(get("/api/history/"+record.id())).andExpect(status().isNotFound());
        mvc.perform(delete("/api/history/"+record.id())).andExpect(status().isNotFound());
    }
    @Test void filtersAndBrowseCapAreStableAndDoNotDeleteData() throws Exception {
        for(int i=0;i<1001;i++)history.insert(record(UUID.randomUUID(),"turn "+i,HistoryRecord.Status.COMPLETED));
        mvc.perform(get("/api/history?page=49&size=20&agentId="+agent)).andExpect(status().isOk()).andExpect(jsonPath("$.visibleTotal").value(1000)).andExpect(jsonPath("$.total").value(1001)).andExpect(jsonPath("$.hasNext").value(false)).andExpect(jsonPath("$.items.length()").value(20));
        mvc.perform(get("/api/history?status=FAILED")).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        mvc.perform(get("/api/history?sessionId="+UUID.randomUUID())).andExpect(status().isOk()).andExpect(jsonPath("$.total").value(0));
        assertEquals(1001,history.list(new HistoryFilter(null,null,null,null,null),0,20).total());
    }
    @Test void aggregatesKnownUsageOnceAndReturnsCollectionStatusAndDailyPoints() throws Exception {
        var run=recorder.begin(Session.builder().id(session).memoryId(UUID.randomUUID()).parentAgentId(agent).build(),null,"REST");
        run.modelStarted(UUID.randomUUID(),"fake").response(ChatResponse.builder().aiMessage(AiMessage.from("answer")).tokenUsage(new TokenUsage(2,3,5)).build());
        run.modelStarted(UUID.randomUUID(),"fake").response(ChatResponse.builder().aiMessage(AiMessage.from("answer")).build());
        run.finish("SUCCEEDED",null,null);recorder.flush();properties.setMaxRuns(0);
        mvc.perform(get("/api/metrics/overview?agentId="+agent)).andExpect(status().isOk()).andExpect(jsonPath("$.summary.runs").value(1)).andExpect(jsonPath("$.summary.modelCalls").value(2)).andExpect(jsonPath("$.summary.totalTokens").value(5)).andExpect(jsonPath("$.summary.usageKnownCalls").value(1)).andExpect(jsonPath("$.collectionEnabled").value(false)).andExpect(jsonPath("$.capabilities.mcpToolCalls").value(0));
    }
    @Test void validatesQueryBoundsAndMapsStorageFailureTo503() throws Exception {
        for(String path:List.of("/api/history?size=101","/api/history?page=50","/api/history?status=RUNNING","/api/metrics/overview?from=invalid","/api/metrics/overview?from=2026-01-01T00:00:00Z&to=2026-10-01T00:00:00Z"))mvc.perform(get(path)).andExpect(status().isBadRequest());
        var broken=MockMvcBuilders.standaloneSetup(new ObservabilityController(filter->{throw new org.springframework.transaction.CannotCreateTransactionException("private JDBC data");}, properties, recorder.health(), recorder.buffer())).build();
        broken.perform(get("/api/metrics/overview")).andExpect(status().isServiceUnavailable()).andExpect(jsonPath("$.code").value("UNAVAILABLE")).andExpect(jsonPath("$.message").value("Archive storage is unavailable"));
        broken.perform(get("/api/metrics/sessions/"+session)).andExpect(status().isServiceUnavailable());
    }

    @Test void sessionMetricsIncludeOldAndFailedRunsAndExcludeOtherSessions() throws Exception {
        var old = Instant.now().minus(Duration.ofDays(120));
        metrics.save(MetricsSnapshot.builder().runId(UUID.randomUUID()).instanceId(UUID.randomUUID()).sessionId(session).agentId(agent)
                .status("SUCCEEDED").startedAt(old).lastObservedAt(old)
                .modelCalls(List.of(MetricsSnapshot.ModelCall.builder().modelCallId(UUID.randomUUID())
                        .startedAt(old).endedAt(old).status("SUCCEEDED").inputTokens(20L).outputTokens(5L).totalTokens(25L).build())).build());
        for (var target : List.of(session, UUID.randomUUID())) {
            var run = recorder.begin(Session.builder().id(target).parentAgentId(agent).memoryId(UUID.randomUUID()).build(), null, "REST");
            run.modelStarted(UUID.randomUUID(), "fake").response(ChatResponse.builder().aiMessage(AiMessage.from("answer")).tokenUsage(new TokenUsage(2, 3, 5)).build());
            run.modelStarted(UUID.randomUUID(), "fake").failed();
            run.finish("FAILED", null, null);
        }
        recorder.flush();
        properties.setMaxRuns(0);
        mvc.perform(get("/api/metrics/sessions/"+session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.sessionId").value(session.toString()))
                .andExpect(jsonPath("$.collectionEnabled").value(false))
                .andExpect(jsonPath("$.summary.runs").value(2))
                .andExpect(jsonPath("$.summary.failedRuns").value(1))
                .andExpect(jsonPath("$.summary.modelCalls").value(3))
                .andExpect(jsonPath("$.summary.inputTokens").value(22))
                .andExpect(jsonPath("$.summary.outputTokens").value(8))
                .andExpect(jsonPath("$.summary.totalTokens").value(30))
                .andExpect(jsonPath("$.summary.usageKnownCalls").value(2));
        mvc.perform(get("/api/metrics/sessions/"+UUID.randomUUID())).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.runs").value(0)).andExpect(jsonPath("$.summary.totalTokens").value(0));
        mvc.perform(get("/api/metrics/sessions/invalid")).andExpect(status().isBadRequest());
    }

    @Test void sessionMetricsPreserveUnknownUsage() throws Exception {
        var run = recorder.begin(Session.builder().id(session).memoryId(UUID.randomUUID()).build(), null, "STREAM");
        run.modelStarted(UUID.randomUUID(), "fake").failed();
        run.finish("CANCELLED", null, null);
        recorder.flush();
        mvc.perform(get("/api/metrics/sessions/"+session)).andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.cancelledRuns").value(1))
                .andExpect(jsonPath("$.summary.totalTokens").value(org.hamcrest.Matchers.nullValue()))
                .andExpect(jsonPath("$.summary.usageKnownCalls").value(0));
    }
}
