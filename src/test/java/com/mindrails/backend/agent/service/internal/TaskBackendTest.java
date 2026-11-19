package com.mindrails.backend.agent.service.internal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.agent.service.*;
import com.mindrails.backend.agent.service.AgentTaskExecutionStreamGateway.*;
import com.mindrails.backend.agent.service.internal.llm.*;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.*;
import com.mindrails.backend.history.store.InMemoryHistoryStore;
import com.mindrails.backend.memory.*;
import com.mindrails.backend.metrics.*;
import com.mindrails.backend.provider.*;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.*;

@Timeout(10)
class TaskBackendTest {
    static <T> T success(ServiceResult<T> value) { assertInstanceOf(ServiceResult.Success.class,value); return ((ServiceResult.Success<T>)value).value(); }
    static void failure(ServiceResult<?> result,ServiceResult.ErrorCode code) { assertEquals(code,assertInstanceOf(ServiceResult.Failure.class,result).code()); }
    static ChatResponse answer(String text) { return ChatResponse.builder().aiMessage(AiMessage.from(text)).finishReason(FinishReason.STOP).tokenUsage(new TokenUsage(2,3,5)).build(); }
    static class Fixture {
        final AgentRegistry agents=new AgentRegistryConfiguration().agentRegistry();
        final TaskRegistry tasks=new InMemoryTaskRegistry();
        final TaskExecutionStore store=new InMemoryTaskExecutionStore();
        final UUID agent=UUID.randomUUID(),other=UUID.randomUUID();
        final Provider provider;
        final DefaultTaskExecutionService executions;
        final DefaultTaskService service;
        final List<TaskService.Changed> changes=new ArrayList<>();
        Fixture(Provider provider,Executor executor,LlmClientFactory factory) {
            this.provider=provider;
            agents.insertIfAbsent(Agent.builder().id(agent).name("agent").systemMessage("Agent policy").createdAt(Instant.now()).build());
            agents.insertIfAbsent(Agent.builder().id(other).name("other").createdAt(Instant.now()).build());
            executions=new DefaultTaskExecutionService(agents,tasks,store,factory,executor,TestHistory.disabled(),TestMetrics.disabled());
            service=new DefaultTaskService(agents,tasks,new SnapshotProviderRegistry(List.of(provider)),executions,e -> changes.add((TaskService.Changed)e));
        }
        Fixture(ChatModel model) { this(Provider.builder().id(UUID.randomUUID()).name("test").modelName("scripted").streaming(false).chatModel(model).build(),Runnable::run,TestMemory.factory(List.of())); }
        UUID create() { return success(service.create(agent,Task.builder().name("Report").instructions("Write a report").build(),provider.getId())); }
        TaskExecutionRequest request() { return new TaskExecutionRequest(UUID.randomUUID(),Map.of("period","today")); }
        UUID submit(UUID task) { return success(executions.submit(agent,task,request(),"REST",TaskExecution.Trigger.api(),null)); }
    }
    static ChatModel model() { return new ChatModel() { public ChatResponse doChat(ChatRequest request) { return answer("done"); } }; }
    static class ManualExecutor implements Executor {
        final Queue<Runnable> work=new ArrayDeque<>();public void execute(Runnable r) { work.add(r); }
        void drain() { while(!work.isEmpty())work.remove().run(); }
    }
    @Test void validatesFieldsAtServiceBoundaryAndMaintainsAgentMembership() {
        var f=new Fixture(model());
        failure(f.service.create(f.agent,Task.builder().instructions(" ").build(),f.provider.getId()),ServiceResult.ErrorCode.INVALID_ARGUMENT);
        failure(f.service.create(f.agent,Task.builder().instructions("do").cronjob("0 0 9 * * *").build(),f.provider.getId()),ServiceResult.ErrorCode.INVALID_ARGUMENT);
        failure(f.service.create(f.other,Task.builder().instructions("do").build(),UUID.randomUUID()),ServiceResult.ErrorCode.NOT_FOUND);
        UUID id=f.create();assertEquals(id,f.agents.findManaged(f.agent).orElseThrow().getTasks().getFirst().getId());
        assertEquals(1,success(f.service.list(f.agent,0,20)).size());
        failure(f.service.get(f.other,id),ServiceResult.ErrorCode.NOT_FOUND);
        failure(f.service.list(f.agent,-1,20),ServiceResult.ErrorCode.INVALID_ARGUMENT);
        var update=Task.builder().name(" New ").instructions("New instructions").cronjob("0 9 * * 1-5").build();
        success(f.service.update(f.agent,id,update,f.provider.getId()));
        assertEquals("New",success(f.service.get(f.agent,id)).getName());assertEquals(1,success(f.service.get(f.agent,id)).getVersion());
        success(f.service.delete(f.agent,id));assertTrue(f.agents.findManaged(f.agent).orElseThrow().getTasks().isEmpty());
        assertEquals(3,f.changes.size());
    }
    @Test void everyExecutionHasFreshMemoryParametersResultAndBusinessRecords() {
        var requests=new ArrayList<ChatRequest>();
        var f=new Fixture(new ChatModel() { public ChatResponse doChat(ChatRequest r) { requests.add(r);return answer("done"); } });
        UUID task=f.create();var first=success(f.executions.executeWithDetailed(f.agent,task,f.request()));
        var second=success(f.executions.executeWithDetailed(f.agent,task,new TaskExecutionRequest(null,Map.of("period","tomorrow"))));
        assertNotEquals(first.executionId(),second.executionId());assertEquals(2,requests.size());
        assertTrue(requests.stream().allMatch(r -> r.messages().stream().filter(m -> m instanceof UserMessage).count()==1));
        assertTrue(requests.stream().allMatch(r -> r.messages().stream().noneMatch(m -> m instanceof AiMessage)));
        assertTrue(requests.get(1).messages().toString().contains("tomorrow"));assertFalse(requests.get(1).messages().toString().contains("today"));
        var stored=success(f.executions.get(f.agent,task,first.executionId()));
        assertEquals(TaskExecution.Status.COMPLETED,stored.status());assertNotNull(stored.result());
        assertEquals(new ChatReply.Usage(2,3,5),stored.usage());assertEquals("Agent policy",stored.taskSnapshot().agentSystemMessage());
        assertTrue(stored.records().stream().anyMatch(e -> e.type().equals("model.requested")));
        assertTrue(stored.records().stream().anyMatch(e -> e.type().equals("model.response.completed")));
        assertEquals(2,success(f.executions.list(f.agent,task,0,20)).size());
        failure(f.executions.get(f.other,task,first.executionId()),ServiceResult.ErrorCode.NOT_FOUND);
        success(f.service.delete(f.agent,task));assertEquals(stored.id(),success(f.executions.get(f.agent,task,stored.id())).id());
    }
    @Test void oneReservationCoversRestWebSocketAndCronAndConfigurationIsFrozen() {
        var queue=new ManualExecutor();var observed=new ArrayList<ChatRequest>();
        var p=Provider.builder().id(UUID.randomUUID()).name("p").streaming(false).chatModel(new ChatModel() { public ChatResponse doChat(ChatRequest r) { observed.add(r);return answer("done"); } }).build();
        var f=new Fixture(p,queue,TestMemory.factory(List.of()));UUID task=f.create();UUID first=f.submit(task);
        var gateway=new DefaultAgentTaskExecutionStreamGateway(f.executions);var scope=new AgentTaskScope(f.agent,task);
        assertEquals(first,success(gateway.validate(scope)).activeExecution().executionId());
        failure(gateway.reserveAndExecute(scope,new Command.Execute(UUID.randomUUID(),Map.of()),e -> {}),ServiceResult.ErrorCode.CONFLICT);
        failure(f.executions.submit(f.agent,task,f.request(),"CRON",new TaskExecution.Trigger(TaskExecution.TriggerType.CRON,null,null,Instant.now()),null),ServiceResult.ErrorCode.CONFLICT);
        failure(f.service.delete(f.agent,task),ServiceResult.ErrorCode.CONFLICT);
        success(f.service.update(f.agent,task,Task.builder().instructions("Changed instructions").build(),p.getId()));
        p.setChatModel(new ChatModel() { public ChatResponse doChat(ChatRequest r) { throw new IllegalStateException("Mutated provider must not replace snapshot"); } });
        queue.drain();assertTrue(observed.getFirst().messages().toString().contains("Write a report"));
        assertEquals(TaskExecution.Status.COMPLETED,success(f.executions.get(f.agent,task,first)).status());
        assertEquals(1,success(f.executions.list(f.agent,task,0,20)).stream().filter(e -> e.status()==TaskExecution.Status.SKIPPED).count());
        assertNull(f.executions.active(task));
    }
    @Test void cancelBeforeWorkerStartsIsDurableAndDoesNotInvokeModel() {
        var queue=new ManualExecutor();ChatModel model=mock(ChatModel.class);
        var p=Provider.builder().id(UUID.randomUUID()).name("p").streaming(false).chatModel(model).build();
        var f=new Fixture(p,queue,TestMemory.factory(List.of()));UUID task=f.create(),id=f.submit(task);
        success(f.executions.cancel(f.agent,task,id));queue.drain();verifyNoInteractions(model);
        assertEquals(TaskExecution.Status.CANCELLED,success(f.executions.get(f.agent,task,id)).status());assertNull(f.executions.active(task));
        failure(f.executions.cancel(f.agent,task,id),ServiceResult.ErrorCode.CONFLICT);
    }
    @Test void rejectionAndClientPreparationFailureStillLeaveExecutionRecords() {
        var factory=mock(LlmClientFactory.class);when(factory.forTask(any(),anyString(),any(),any())).thenReturn(ServiceResult.failure(ServiceResult.ErrorCode.NOT_IMPLEMENTED,"Missing model"));
        var p=Provider.builder().id(UUID.randomUUID()).name("p").build();var f=new Fixture(p,Runnable::run,factory);var task=f.create();
        var id=f.submit(task);assertEquals("NOT_IMPLEMENTED",success(f.executions.get(f.agent,task,id)).error().code());assertNull(f.executions.active(task));
        var rejected=new Fixture(p,r -> { throw new RejectedExecutionException(); },factory);task=rejected.create();id=rejected.submit(task);
        assertEquals("EXECUTOR_UNAVAILABLE",success(rejected.executions.get(rejected.agent,task,id)).error().code());
    }
    @Test void aDisconnectedSinkDoesNotCancelTheTask() {
        var f=new Fixture(model());var task=f.create();UUID id=success(f.executions.submit(f.agent,task,f.request(),"WEBSOCKET",TaskExecution.Trigger.api(),e -> { throw new IllegalStateException("closed"); }));
        assertEquals(TaskExecution.Status.COMPLETED,success(f.executions.get(f.agent,task,id)).status());
    }
    static class Ping implements IdentifiedTool {
        public UUID id() { return UUID.randomUUID(); }
        @Tool("Test tool") public String ping() { return "pong"; }
    }
    @Test void streamingToolLoopPersistsEveryModelRoundToolResultAndAggregateTokens() {
        AtomicInteger calls=new AtomicInteger();
        var model=new StreamingChatModel() { public void doChat(ChatRequest request,StreamingChatResponseHandler handler) {
            if(calls.incrementAndGet()==1)handler.onCompleteResponse(ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("ping-1").name("ping").arguments("{}").build()))
                    .finishReason(FinishReason.TOOL_EXECUTION).tokenUsage(new TokenUsage(2,3,5)).build());
            else { handler.onPartialResponse("done");handler.onCompleteResponse(answer("done")); }
        } };
        var p=Provider.builder().id(UUID.randomUUID()).name("p").streaming(true).streamingChatModel(model).build();
        var f=new Fixture(p,Runnable::run,TestMemory.factory(List.of(new Ping())));UUID task=f.create();var events=new ArrayList<Event>();
        var id=success(f.executions.submit(f.agent,task,f.request(),"WEBSOCKET",TaskExecution.Trigger.api(),events::add));
        var result=success(f.executions.get(f.agent,task,id));
        assertEquals(TaskExecution.Status.COMPLETED,result.status());assertEquals(2,calls.get());
        assertEquals(1,result.toolExecutions().size());assertEquals("pong",result.toolExecutions().getFirst().result());
        assertEquals(new ChatReply.Usage(4,6,10),result.usage());
        assertEquals(2,result.records().stream().filter(e -> e.type().equals("model.response.completed")).count());
        assertEquals("execution.accepted",events.getFirst().type());assertEquals("execution.completed",events.getLast().type());
        assertTrue(events.stream().anyMatch(e -> e.type().equals("tool.started")));
        assertTrue(events.stream().anyMatch(e -> e.type().equals("answer.delta")));
        assertTrue(events.stream().allMatch(e -> id.equals(e.executionId())));
    }
    @Test void cancellationKeepsReservationUntilAnActiveToolStops() throws Exception {
        var entered=new CountDownLatch(1);var release=new CountDownLatch(1);var done=new CountDownLatch(1);
        var tool=new IdentifiedTool() {
            public UUID id() { return UUID.randomUUID(); }
            @Tool("Block") public String block() {
                entered.countDown();boolean interrupted=false;
                while(true) { try { release.await();break; }catch(InterruptedException e) { interrupted=true; } }
                if(interrupted)Thread.currentThread().interrupt();return "finished";
            }
        };
        var calls=new AtomicInteger();var model=new ChatModel() { public ChatResponse doChat(ChatRequest r) {
            return calls.incrementAndGet()==1?ChatResponse.builder().aiMessage(AiMessage.from(ToolExecutionRequest.builder().id("block").name("block").arguments("{}").build())).build():answer("done");
        } };
        try(var pool=Executors.newFixedThreadPool(2)) {
            var p=Provider.builder().id(UUID.randomUUID()).name("p").streaming(false).chatModel(model).build();
            var f=new Fixture(p,pool,TestMemory.factory(List.of(tool)));UUID task=f.create();
            UUID id=success(f.executions.submit(f.agent,task,f.request(),"REST",TaskExecution.Trigger.api(),e -> { if(e.type().equals("execution.cancelled"))done.countDown(); }));
            try {
                assertTrue(entered.await(3,TimeUnit.SECONDS));success(f.executions.cancel(f.agent,task,id));
                assertEquals(TaskExecution.Status.CANCELLING,success(f.executions.get(f.agent,task,id)).status());
                assertNotNull(f.executions.active(task));failure(f.executions.submit(f.agent,task,f.request(),"REST",TaskExecution.Trigger.api(),null),ServiceResult.ErrorCode.CONFLICT);
            } finally { release.countDown(); }
            assertTrue(done.await(3,TimeUnit.SECONDS));assertEquals(1,calls.get());assertNull(f.executions.active(task));
        }
    }
    @Test void historyAndMetricsUseTaskIdentityAndSameExecutionId() {
        var f=new Fixture(model());var archive=new InMemoryHistoryStore();var history=TestHistory.create(archive,f.agents);
        var props=new MetricsProperties();var health=new MetricsHealth();var repo=new InMemoryMetricsRepository(props,health);
        try(var recording=new TestMetrics(props,repo,health,false)) {
            var factory=TestLlmClients.create(List.of(),dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore::new,new ChatMemoryProperties(),null,null,null,null,history,recording.recorder());
            var executions=new DefaultTaskExecutionService(f.agents,f.tasks,f.store,factory,Runnable::run,history,recording.recorder());
            UUID task=f.create();var reply=success(executions.executeWithDetailed(f.agent,task,f.request()));recording.flush();
            var archived=archive.find(reply.executionId()).orElseThrow();assertEquals(task,archived.taskId());assertNull(archived.sessionId());
            var metrics=repo.snapshots().getFirst();assertEquals(reply.executionId(),metrics.runId());assertEquals(task,metrics.taskId());assertNull(metrics.sessionId());
            assertEquals(1,metrics.modelCallCount());assertEquals("SUCCEEDED",metrics.status());
        }
    }
    @Test void terminalRecordsIgnoreLateWritesAndRecoveryDoesNotReplay() {
        var f=new Fixture(model());UUID task=f.create(),id=f.submit(task);var complete=f.store.find(id).orElseThrow();
        f.store.save(complete.toBuilder().status(TaskExecution.Status.RUNNING).build());
        f.store.append(id,new TaskExecution.Entry(1000,Instant.now(),"late",Map.of()));assertEquals(complete,f.store.find(id).orElseThrow());
        var pending=complete.toBuilder().id(UUID.randomUUID()).status(TaskExecution.Status.RUNNING).result(null).build();f.store.insert(pending);
        f.store.recoverInterrupted();assertEquals("EXECUTION_INTERRUPTED",f.store.find(pending.id()).orElseThrow().error().code());
    }
    @Test void requiredResultPersistenceFailureCannotReturnASuccessfulReply() {
        var f=new Fixture(model());UUID task=f.create();var store=spy(new InMemoryTaskExecutionStore());
        doAnswer(inv -> {
            TaskExecution record=inv.getArgument(0);
            if(record.status()==TaskExecution.Status.COMPLETED)throw new IllegalStateException("Commit failed");
            return inv.callRealMethod();
        }).when(store).save(any());
        var executions=new DefaultTaskExecutionService(f.agents,f.tasks,store,TestMemory.factory(List.of()),Runnable::run,TestHistory.disabled(),TestMetrics.disabled());
        failure(executions.executeWithDetailed(f.agent,task,f.request()),ServiceResult.ErrorCode.INTERNAL_ERROR);
        var record=store.list(f.agent,task,0,20).getFirst();
        assertEquals(TaskExecution.Status.FAILED,record.status());assertEquals("EXECUTION_STORAGE_UNAVAILABLE",record.error().code());
        assertNull(record.result());assertNull(executions.active(task));
    }
    @Test void stalledStreamTimesOutWithADurableFailure() throws Exception {
        var model=new StreamingChatModel() { public void doChat(ChatRequest r,StreamingChatResponseHandler handler) {} };
        var p=Provider.builder().id(UUID.randomUUID()).name("p").streaming(true).streamingChatModel(model).build();
        var f=new Fixture(p,Runnable::run,TestMemory.factory(List.of()));
        org.springframework.test.util.ReflectionTestUtils.setField(f.executions,"timeout",Duration.ofMillis(30));
        var done=new CountDownLatch(1);UUID task=f.create();
        UUID id=success(f.executions.submit(f.agent,task,f.request(),"WEBSOCKET",TaskExecution.Trigger.api(),e -> { if(e.type().equals("execution.failed"))done.countDown(); }));
        assertTrue(done.await(3,TimeUnit.SECONDS));var record=success(f.executions.get(f.agent,task,id));
        assertEquals(TaskExecution.Status.FAILED,record.status());assertEquals("EXECUTION_TIMEOUT",record.error().code());assertNull(f.executions.active(task));
    }
}
