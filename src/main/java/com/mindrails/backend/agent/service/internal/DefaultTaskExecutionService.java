package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.agent.service.AgentTaskExecutionStreamGateway.*;
import com.mindrails.backend.agent.service.internal.llm.*;
import com.mindrails.backend.common.ServiceResult;
import static com.mindrails.backend.common.ServiceResult.ErrorCode.*;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.history.service.internal.HistoryRecords;
import com.mindrails.backend.metrics.*;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.*;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.tool.ToolExecution;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import org.springframework.beans.factory.annotation.*;
import org.springframework.stereotype.Service;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** All Task triggers share reservation, snapshots, business recording, cancellation and finalization. */
@Service
@lombok.extern.slf4j.Slf4j
public class DefaultTaskExecutionService {
    private final AgentRegistry agents;
    private final TaskRegistry tasks;
    private final TaskExecutionStore store;
    private final LlmClientFactory factory;
    private final Executor executor;
    private final ChatHistory history;
    private final MetricsRecorder metrics;
    private final ConcurrentMap<UUID, Run> runs = new ConcurrentHashMap<>();
    private final JsonMapper json = JsonMapper.builder().build();
    @Value("${mindrails.tasks.execution-timeout:PT30M}") private Duration timeout = Duration.ofMinutes(30);
    public DefaultTaskExecutionService(AgentRegistry agents, TaskRegistry tasks, TaskExecutionStore store,
            LlmClientFactory factory, @Qualifier("taskExecutionExecutor") Executor executor,
            ChatHistory history, MetricsRecorder metrics) {
        this.agents=agents; this.tasks=tasks; this.store=store; this.factory=factory;
        this.executor=executor; this.history=history; this.metrics=metrics;
    }
    @jakarta.annotation.PostConstruct public void recoverInterrupted() {
        for(var e:store.recoverInterrupted()) {
            var values=new HashMap<String,Object>();
            values.put(LlmClient.OWNER_ID,com.mindrails.backend.identity.ResourceAccess.owner(e.ownerId()));values.put(LlmClient.RUN_ID,e.id());values.put(LlmClient.TASK_ID,e.taskId());values.put(LlmClient.AGENT_ID,e.agentId());
            values.put(LlmClient.MEMORY_ID,e.id());values.put(LlmClient.REQUEST_ID,e.requestId());values.put(LlmClient.STARTED_AT,e.createdAt());
            values.put(LlmClient.PROVIDER_ID,e.taskSnapshot().providerId());values.put(LlmClient.PROVIDER_NAME,e.taskSnapshot().providerName());
            values.put(LlmClient.MODEL_NAME,e.taskSnapshot().modelName());values.put(LlmClient.SESSION_VERSION,e.taskSnapshot().version());
            values.put(LlmClient.SKILL_IDS,e.taskSnapshot().skillIds());values.put(LlmClient.TRANSPORT,"RECOVERY");
            values.values().removeIf(Objects::isNull);
            var parameters=InvocationParameters.from(values);
            history.record(HistoryRecords.failed(parameters,e.error().code(),e.error().message()));
            metrics.record(MetricsRecords.finish(parameters,"INTERRUPTED",e.error().code(),e.error().code(),null));
        }
    }

    public ServiceResult<Task> owned(UUID agent, UUID task) {
        if (agent == null || task == null) return ServiceResult.failure(INVALID_ARGUMENT,"Agent and Task IDs are required");
        if (agents.findManaged(agent).isEmpty()) return ServiceResult.failure(NOT_FOUND,"Agent not found");
        return tasks.find(task).filter(t -> agent.equals(t.getParentAgentId())).<ServiceResult<Task>>map(ServiceResult::success)
                .orElseGet(() -> ServiceResult.failure(NOT_FOUND,"Task not found"));
    }
    public ActiveExecution active(UUID task) {
        Run run=runs.get(task); return run == null ? null : new ActiveExecution(run.record.id(),run.record.status());
    }
    public ServiceResult<UUID> submit(UUID agent, UUID task, TaskExecutionRequest request, String transport,
            TaskExecution.Trigger trigger, EventSink sink) {
        return reserve(agent,task,request,transport,trigger,sink).map(run -> run.record.id());
    }
    public ServiceResult<TaskReply> executeWithDetailed(UUID agent, UUID task, TaskExecutionRequest request) {
        return reserve(agent,task,request,"REST",TaskExecution.Trigger.api(),null).flatMap(run -> {
            try { return run.finished.get(); }
            catch (InterruptedException e) { Thread.currentThread().interrupt(); return ServiceResult.failure(UNAVAILABLE,"Waiting interrupted; executionId="+run.record.id()); }
            catch (ExecutionException e) { return ServiceResult.failure(INTERNAL_ERROR,"Execution failed; executionId="+run.record.id()); }
        });
    }
    private ServiceResult<Run> reserve(UUID agent, UUID taskId, TaskExecutionRequest request, String transport,
            TaskExecution.Trigger trigger, EventSink sink) {
        if (request == null) return ServiceResult.failure(INVALID_ARGUMENT,"Execution request is required");
        final Map<String,Object> parameters;
        try {
            byte[] encoded=json.writeValueAsBytes(request.parameters());
            if(encoded.length>65536) return ServiceResult.failure(INVALID_ARGUMENT,"Execution parameters exceed 64 KiB");
            parameters=json.readValue(encoded,new TypeReference<Map<String,Object>>() {});
        } catch(RuntimeException e) { return ServiceResult.failure(INVALID_ARGUMENT,"Parameters must be a JSON object"); }
        Run run;
        synchronized(tasks) {
            var found=owned(agent,taskId);
            if(found instanceof ServiceResult.Failure<Task> f) return ServiceResult.failure(f.code(),f.message());
            var task=((ServiceResult.Success<Task>)found).value().snapshot();
            var parent=agents.findManaged(agent).orElse(null);
            if(parent==null) return ServiceResult.failure(NOT_FOUND,"Agent not found");
            var now=Instant.now(); var provider=task.getProvider();
            var record=TaskExecution.builder().id(UUID.randomUUID()).ownerId(parent.getOwnerId()).agentId(agent).taskId(taskId)
                    .requestId(request.requestId()==null?UUID.randomUUID():request.requestId()).trigger(trigger)
                    .parameters(parameters).taskSnapshot(new TaskExecution.Definition(task.getVersion(),task.getName(),task.getInstructions(),
                            parent.getSystemMessage(),provider.getId(),provider.getName(),provider.getModelName(),task.getSkillIds(),task.getCronjob()))
                    .status(TaskExecution.Status.STARTING).createdAt(now).build();
            if(runs.containsKey(taskId)) {
                if(trigger.type()==TaskExecution.TriggerType.CRON) store.insert(record.toBuilder().status(TaskExecution.Status.SKIPPED)
                        .finishedAt(now).durationMs(0L).error(new TaskExecution.Failure("TASK_BUSY","Previous execution is still active")).build());
                return ServiceResult.failure(CONFLICT,"Task already has an active execution");
            }
            store.insert(record); run=new Run(task,record,transport,sink); runs.put(taskId,run);
        }
        run.event("execution.accepted",Map.of("status",TaskExecution.Status.STARTING));
        try { executor.execute(com.mindrails.backend.identity.ResourceAccess.capture(run::start)); }
        catch(RuntimeException e) { run.fail("EXECUTOR_UNAVAILABLE","Execution executor is unavailable"); }
        return ServiceResult.success(run);
    }
    public ServiceResult<List<TaskExecution>> list(UUID agent,UUID task,int page,int size) {
        if(agent==null || task==null || page<0 || size<1 || size>100) return ServiceResult.failure(INVALID_ARGUMENT,"Invalid IDs or pagination");
        if(agents.findManaged(agent).isEmpty()) return ServiceResult.failure(NOT_FOUND,"Agent not found");
        // Completed executions remain queryable after deleting their Task definition.
        return ServiceResult.success(store.list(agent,task,page,size));
    }
    public ServiceResult<TaskExecution> get(UUID agent,UUID task,UUID execution) {
        if(agent==null || task==null || execution==null) return ServiceResult.failure(INVALID_ARGUMENT,"Agent, Task and execution IDs are required");
        if(agents.findManaged(agent).isEmpty()) return ServiceResult.failure(NOT_FOUND,"Agent not found");
        return store.find(execution).filter(e -> agent.equals(e.agentId()) && task.equals(e.taskId()))
                .<ServiceResult<TaskExecution>>map(ServiceResult::success).orElseGet(() -> ServiceResult.failure(NOT_FOUND,"Execution not found"));
    }
    public ServiceResult<Void> cancel(UUID agent,UUID task,UUID execution) {
        return get(agent,task,execution).flatMap(e -> {
            Run run=runs.get(task);
            if(run==null || !run.record.id().equals(execution)) return ServiceResult.failure(CONFLICT,"Execution is no longer active");
            run.cancel(); return ServiceResult.success();
        });
    }
    @jakarta.annotation.PreDestroy public void stop() { runs.values().forEach(Run::cancel); }

    private final class Run implements TaskExecutionObserver {
        final Task task;
        volatile TaskExecution record;
        final InvocationParameters parameters;
        final EventSink sink;
        final CompletableFuture<Void> cancellation=new CompletableFuture<>();
        final CompletableFuture<Void> deadline=new CompletableFuture<>();
        TaskExecution.Failure stopReason;
        final CompletableFuture<ServiceResult<TaskReply>> finished=new CompletableFuture<>();
        final List<TaskExecution.ToolCall> tools=new ArrayList<>();
        final List<ChatReply.Source> sources=new ArrayList<>();
        final Set<ToolExecutionRequest> activeTools=new HashSet<>();
        final StringBuilder partial=new StringBuilder();
        final StringBuilder partialThinking=new StringBuilder();
        StreamingHandle handle;
        Thread worker;
        boolean synchronous, cancelRequested, terminal;
        long sequence;
        int round;
        final Set<Integer> announcedTools = new HashSet<>();
        UUID messageId;
        TokenUsage aggregate;
        String finishReason;
        Run(Task task,TaskExecution record,String transport,EventSink sink) {
            this.task=task;this.record=record;this.sink=sink;
            parameters=LlmClient.parameters(task,record.id(),record.requestId(),record.createdAt(),transport);
            parameters.put(LlmClient.CANCELLATION,cancellation);
            parameters.put(LlmClient.TOOL_SOURCES,new ConcurrentHashMap<String,Object>());
        }
        synchronized void start() {
            if(terminal) return;
            worker=Thread.currentThread();
            String input=task.getInstructions()+"\n\nExecution parameters (JSON data):\n"+json.writeValueAsString(record.parameters());
            history.record(HistoryRecords.start(parameters,input)); metrics.record(MetricsRecords.start(parameters));
            try {
                append("execution.input",Map.of("instructions",task.getInstructions(),"parameters",record.parameters()));
                record=record.toBuilder().status(TaskExecution.Status.RUNNING).startedAt(Instant.now()).build(); store.save(record);
                event("execution.started",Map.of("status",record.status()));
                var result=factory.forTask(task,record.taskSnapshot().agentSystemMessage(),record.id(),this);
                if(result instanceof ServiceResult.Failure<LlmClient> f) { fail(f.code().name(),f.message());return; }
                LlmClient client=((ServiceResult.Success<LlmClient>)result).value();
                deadline.orTimeout(timeout.toMillis(),TimeUnit.MILLISECONDS).whenCompleteAsync((ignored,error) -> {
                    synchronized(this) {
                        if(error!=null && !terminal) {
                            stopReason=new TaskExecution.Failure("EXECUTION_TIMEOUT","Execution exceeded its time limit");
                            cancel();
                        }
                    }
                });
                if(Boolean.TRUE.equals(task.getProvider().getStreaming())) stream(client,input);
                else {
                    synchronous=true;
                    // Model/tool work must run outside the Run monitor so cancel can signal it.
                    executor.execute(() -> sync(client,input));
                }
            } catch(RuntimeException e) { failure(e); }
            finally { worker=null; }
        }
        void sync(LlmClient client,String input) {
            synchronized(this) { if(terminal)return;worker=Thread.currentThread(); }
            try {
                var result=client.chatWithLines(record.id(),input,parameters);
                synchronized(this) {
                    if(result.sources()!=null) retrieved(result.sources());
                    complete(result.content(),result.tokenUsage(),result.finishReason()==null?null:result.finishReason().name());
                }
            } catch(RuntimeException e) { synchronized(this) { failure(e); } }
            finally { synchronized(this) { worker=null; } }
        }
        void stream(LlmClient client,String input) {
            // Memory and RAG preparation are also cancellable and must not hold this monitor.
            executor.execute(() -> {
                try {
                    if(cancellation.isDone())return;
                    var stream=client.chatWithStream(record.id(),input,parameters);
                    stream.onRetrieved(contents -> { synchronized(this) { if(!terminal)retrieved(contents); } })
                        .onPartialResponseWithContext((response,ctx) -> { synchronized(this) {
                            if(!accept(ctx.streamingHandle()))return;
                            partial.append(response.text());metrics.record(MetricsRecords.firstResponse(parameters,Instant.now()));
                            event("answer.delta",Map.of("round",round,"messageId",messageId,"text",response.text()));
                        } })
                        .onPartialThinkingWithContext((thinking,ctx) -> { synchronized(this) {
                            if(accept(ctx.streamingHandle())) {
                                partialThinking.append(thinking.text());
                                event("thinking.delta",Map.of("round",round,"messageId",messageId,"text",thinking.text()));
                            }
                        } })
                        .onPartialToolCall(tool -> { synchronized(this) {
                            if(!terminal && tool.name()!=null && announcedTools.add(tool.index())) event("tool.arguments.delta",Map.of("round",round,"index",tool.index(),"toolName",tool.name(),"text",""));
                        } })
                        .onCompleteResponse(response -> { synchronized(this) {
                            complete(List.of(Objects.toString(response.aiMessage().text(),"")),response.tokenUsage(),response.finishReason()==null?null:response.finishReason().name());
                        } })
                        .onError(error -> { synchronized(this) { failure(error); } });
                    synchronized(this) { if(terminal)return; }
                    stream.start();
                } catch(RuntimeException e) { synchronized(this) { failure(e); } }
            });
        }
        boolean accept(StreamingHandle next) {
            handle=next;
            if(terminal || cancelRequested) { next.cancel();return false; }
            return true;
        }
        public synchronized void modelRequested(List<ChatMessage> messages) {
            check();round++;announcedTools.clear();messageId=UUID.randomUUID();partial.setLength(0);partialThinking.setLength(0);
            append("model.requested",Map.of("round",round,"messages",ChatMessageSerializer.messagesToJson(messages)));
        }
        public synchronized void modelResponded(ChatResponse response) {
            if(terminal)return;
            aggregate=TokenUsage.sum(aggregate,response.tokenUsage());
            var data=new LinkedHashMap<String,Object>();data.put("round",round);data.put("messageId",messageId);
            data.put("message",ChatMessageSerializer.messagesToJson(List.of(response.aiMessage())));
            data.put("finishReason",response.finishReason());data.put("usage",usage(response.tokenUsage()));
            append("model.response.completed",data);event("model.response.completed",data);
        }
        public synchronized void toolStarted(ToolExecutionRequest request) {
            check();activeTools.add(request);
            var data=Map.of("round",round,"toolCallId",Objects.toString(request.id(),""),"toolName",request.name(),"arguments",Objects.toString(request.arguments(),"{}"));
            append("tool.started",data);event("tool.started",data);
        }
        public synchronized void toolCompleted(ToolExecution execution) {
            activeTools.remove(execution.request());
            if(terminal)return;
            var request=execution.request();
            var call=new TaskExecution.ToolCall(request.id(),request.name(),request.arguments(),com.mindrails.backend.tool.ToolResultText.from(execution.resultContents()),execution.hasFailed(),execution.duration()==null?null:execution.duration().toMillis());
            tools.add(call);append(execution.hasFailed()?"tool.failed":"tool.completed",call);event(execution.hasFailed()?"tool.failed":"tool.completed",call);
            if(cancelRequested && activeTools.isEmpty() && !synchronous)finishCancelled();
        }
        void retrieved(List<dev.langchain4j.rag.content.Content> contents) {
            for(var c:contents) { var s=c.textSegment();sources.add(new ChatReply.Source(s.metadata().getString("id"),s.metadata().getString("key"),s.text(),s.metadata().getString("url"))); }
            if(!contents.isEmpty()) { append("retrieval.completed",List.copyOf(sources));event("retrieval.completed",List.copyOf(sources)); }
        }
        synchronized void cancel() {
            if(terminal || cancelRequested)return;
            cancelRequested=true;cancellation.complete(null);
            record=record.toBuilder().status(TaskExecution.Status.CANCELLING).build();store.save(record);
            event("execution.cancelling",Map.of("status",record.status()));
            if(handle!=null) {
                try { handle.cancel(); } catch(RuntimeException e) { log.warn("Provider stream cancellation failed: executionId={}",record.id(),e); }
            }
            if(worker!=null && synchronous)worker.interrupt();
            if(!synchronous && activeTools.isEmpty())finishCancelled();
        }
        void check() { if(terminal || cancelRequested)throw new CancellationException("Task execution cancelled"); }
        void complete(List<String> lines,TokenUsage tokens,String finishReason) {
            if(terminal)return;
            if(cancelRequested) { finishCancelled();return; }
            var reply=new TaskReply(record.id(),lines,sources,usage(tokens==null?aggregate:tokens),tools.stream().map(TaskExecution.ToolCall::name).toList());
            this.finishReason=finishReason;
            if(tokens!=null)aggregate=tokens;
            finish(TaskExecution.Status.COMPLETED,reply,null);
        }
        void failure(Throwable e) {
            if(terminal)return;
            if(cancelRequested || e instanceof CancellationException)finishCancelled();
            else { log.warn("Task execution failed: executionId={}",record.id(),e);fail("EXECUTION_FAILED","Task execution failed"); }
        }
        synchronized void fail(String code,String message) {
            if(terminal)return;
            finish(TaskExecution.Status.FAILED,null,new TaskExecution.Failure(code,message));
        }
        void finishCancelled() {
            if(terminal)return;
            if(stopReason!=null) { fail(stopReason.code(),stopReason.message());return; }
            finish(TaskExecution.Status.CANCELLED,null,new TaskExecution.Failure("CANCELLED","Execution cancelled"));
        }
        void finish(TaskExecution.Status status,TaskReply reply,TaskExecution.Failure error) {
            if(terminal)return;
            var now=Instant.now();
            var next=record.toBuilder().status(status).finishedAt(now).durationMs(Duration.between(record.createdAt(),now).toMillis())
                    .result(reply).error(error).usage(reply==null?usage(aggregate):reply.usage()).toolExecutions(tools).build();
            try {
                if(partial.length()>0)append("answer.partial",partial.toString());
                if(partialThinking.length()>0)append("thinking.partial",partialThinking.toString());
                append("execution."+status.name().toLowerCase(Locale.ROOT),reply==null?error:reply);
                store.save(next);
            } catch(RuntimeException persistenceFailure) {
                // Never report success when the required business result could not be committed.
                log.error("Task result persistence failed: executionId={}",record.id(),persistenceFailure);
                status=TaskExecution.Status.FAILED;reply=null;
                error=new TaskExecution.Failure("EXECUTION_STORAGE_UNAVAILABLE","Execution result could not be saved");
                next=next.toBuilder().status(status).result(null).error(error).build();
                try { store.save(next); } catch(RuntimeException retryFailure) { log.error("Task failure persistence failed: executionId={}",record.id(),retryFailure); }
            }
            record=next;terminal=true;deadline.complete(null);
            if(status==TaskExecution.Status.COMPLETED) {
                history.record(HistoryRecords.finish(parameters,com.mindrails.backend.history.model.HistoryRecord.Status.COMPLETED,reply.lines(),aggregate,finishReason,null,null));
                metrics.record(MetricsRecords.finish(parameters,"SUCCEEDED",null,null,aggregate));
            } else if(status==TaskExecution.Status.CANCELLED) {
                history.record(HistoryRecords.cancelled(parameters,"USER_CANCELLED"));metrics.record(MetricsRecords.finish(parameters,"CANCELLED","USER_CANCELLED",null,aggregate));
            } else {
                history.record(HistoryRecords.failed(parameters,error.code(),error.message()));metrics.record(MetricsRecords.finish(parameters,"FAILED",error.code(),error.code(),aggregate));
            }
            runs.remove(task.getId(),this);
            event("execution."+status.name().toLowerCase(Locale.ROOT),reply==null?error:reply);
            finished.complete(reply!=null?ServiceResult.success(reply):ServiceResult.failure(status==TaskExecution.Status.CANCELLED?CONFLICT:INTERNAL_ERROR,
                    (error==null?"Task execution failed":error.message())+"; executionId="+record.id()));
        }
        void append(String type,Object data) { store.append(record.id(),new TaskExecution.Entry(++sequence,Instant.now(),type,data)); }
        void event(String type,Object data) {
            if(sink==null)return;
            try { sink.emit(new Event(type,record.id(),record.requestId(),Instant.now(),com.mindrails.backend.tool.display.ToolUiProjection.taskEvent(type,data))); }
            catch(RuntimeException e) { log.debug("Task event sink disconnected: executionId={}",record.id()); }
        }
    }
    private static ChatReply.Usage usage(TokenUsage tokens) { return tokens==null?null:new ChatReply.Usage(tokens.inputTokenCount(),tokens.outputTokenCount(),tokens.totalTokenCount()); }
}
