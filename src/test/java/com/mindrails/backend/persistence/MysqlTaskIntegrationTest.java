package com.mindrails.backend.persistence;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.agent.service.internal.*;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.*;
import com.mindrails.backend.history.store.*;
import com.mindrails.backend.memory.TestMemory;
import com.mindrails.backend.metrics.*;
import com.mindrails.backend.provider.*;
import com.zaxxer.hikari.HikariDataSource;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.json.JsonMapper;

/** Real MySQL round trips; scripted models, only test-owned resources are removed. */
class MysqlTaskIntegrationTest {
    final HikariDataSource source=new HikariDataSource();
    final JdbcTemplate jdbc;
    final UUID agent=UUID.randomUUID();
    final List<UUID> executions=new ArrayList<>();
    final Provider provider=Provider.builder().id(UUID.randomUUID()).name("task-test").modelName("scripted").streaming(false)
            .chatModel(new ChatModel() { public ChatResponse doChat(ChatRequest r) { return ChatResponse.builder().aiMessage(AiMessage.from("done")).tokenUsage(new TokenUsage(2,3,5)).build(); } }).build();
    final SnapshotProviderRegistry providers=new SnapshotProviderRegistry(List.of(provider));
    final JsonMapper json=JsonMapper.builder().build();
    final TaskRegistry tasks;
    final MysqlAgentRegistry agents;
    MysqlTaskIntegrationTest() {
        source.setJdbcUrl(System.getenv().getOrDefault("MINDRAILS_MYSQL_URL","jdbc:mysql://127.0.0.1:3306/mindrails"));
        source.setUsername(System.getenv().getOrDefault("MINDRAILS_MYSQL_USERNAME","mindrails_app"));
        source.setPassword(System.getenv("MINDRAILS_MYSQL_PASSWORD"));
        source.addDataSourceProperty("connectionTimeZone","UTC");source.addDataSourceProperty("forceConnectionTimeZoneToSession","true");
        jdbc=new JdbcTemplate(source);tasks=new MysqlTaskRegistry(jdbc,json,providers);
        agents=new MysqlAgentRegistry(jdbc,json,new SessionRegistryConfiguration().sessionRegistry(),tasks);
        agents.insertIfAbsent(Agent.builder().id(agent).name("task-db-test").createdAt(Instant.now()).updatedAt(Instant.now()).build());
    }
    @AfterEach void clean() {
        try {
            for(UUID id:executions) {
                for(String table:List.of("metrics_model_calls","metrics_capability_calls","metrics_runs"))jdbc.update("DELETE FROM "+table+" WHERE run_id=?",id.toString());
                jdbc.update("DELETE FROM history WHERE id=?",id.toString());jdbc.update("DELETE FROM task_executions WHERE id=?",id.toString());
            }
            jdbc.update("DELETE FROM tasks WHERE parent_agent_id=?",agent.toString());jdbc.update("DELETE FROM agents WHERE id=?",agent.toString());
        } finally { source.close(); }
    }
    static <T> T success(ServiceResult<T> r) { assertInstanceOf(ServiceResult.Success.class,r);return ((ServiceResult.Success<T>)r).value(); }
    @Test void definitionsAndExecutionsRecoverAcrossFreshRepositoriesWithoutSessionRows() {
        var store=new MysqlTaskExecutionStore(jdbc,json);
        var executor=new DefaultTaskExecutionService(agents,tasks,store,TestMemory.factory(List.of()),Runnable::run,TestHistory.disabled(),TestMetrics.disabled());
        var service=new DefaultTaskService(agents,tasks,providers,executor,event -> {});
        UUID task=success(service.create(agent,Task.builder().name("nightly").instructions("Report").cronjob("0 9 * * 1-5").build(),provider.getId()));
        assertEquals(task,agents.findManaged(agent).orElseThrow().getTasks().getFirst().getId());
        var reply=success(executor.executeWithDetailed(agent,task,new TaskExecutionRequest(null,Map.of("day","today"))));executions.add(reply.executionId());
        var restored=new MysqlTaskExecutionStore(jdbc,json).find(reply.executionId()).orElseThrow();
        assertEquals(TaskExecution.Status.COMPLETED,restored.status());assertEquals("today",restored.parameters().get("day"));assertEquals(reply,restored.result());
        assertTrue(restored.records().size()>=4);assertEquals("0 9 * * 1-5",new MysqlTaskRegistry(jdbc,json,providers).find(task).orElseThrow().getCronjob());
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sessions WHERE parent_agent_id=?",Integer.class,agent.toString()));
        store.save(restored.toBuilder().status(TaskExecution.Status.RUNNING).build());
        store.append(restored.id(),new TaskExecution.Entry(999,Instant.now(),"late",Map.of()));
        assertEquals(restored,new MysqlTaskExecutionStore(jdbc,json).find(restored.id()).orElseThrow());
        success(service.delete(agent,task));assertTrue(store.find(restored.id()).isPresent());
    }
    @Test void taskArchivesAndMetricsRoundTripNullSessionAndExplicitTaskIdentity() {
        var now=Instant.now();UUID task=UUID.randomUUID(),id=UUID.randomUUID();executions.add(id);
        var definition=Task.builder().id(task).parentAgentId(agent).provider(provider).instructions("Test").createdAt(now).updatedAt(now).build();tasks.insertIfAbsent(definition);
        var p=LlmClient.parameters(definition,id,UUID.randomUUID(),now,"CRON");
        var history=new MysqlHistoryStore(jdbc);history.insert(com.mindrails.backend.history.service.internal.HistoryRecords.start(p,"Test"));
        history.insert(com.mindrails.backend.history.service.internal.HistoryRecords.failed(p,"TEST_FAILURE","scripted"));
        var archive=new MysqlHistoryQueryStore(jdbc).find(id).orElseThrow();assertEquals(task,archive.taskId());assertNull(archive.sessionId());
        assertEquals(com.mindrails.backend.history.model.HistoryRecord.Transport.SYNC,archive.transport());
        var properties=new MetricsProperties();var repository=new MysqlMetricsRepository(jdbc,properties,new MetricsHealth());
        repository.save(MetricsRecords.start(p));repository.save(MetricsRecords.finish(p,"SUCCEEDED",null,null,new TokenUsage(2,3,5)));
        var row=jdbc.queryForMap("SELECT task_id,session_id,status FROM metrics_runs WHERE run_id=?",id.toString());
        assertEquals(task.toString(),row.get("task_id"));assertNull(row.get("session_id"));assertEquals("SUCCEEDED",row.get("status"));
    }
}
