package com.mindrails.backend.agent.service.internal;

import static com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamServiceHelper.*;

import com.mindrails.backend.agent.api.websocket.protocol.CancelReason;
import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.EventSink;
import com.mindrails.backend.agent.service.internal.DefaultLoopSessionStreamService.MessageToLLM;
import com.mindrails.backend.common.ExceptionDiagnostics;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.history.service.internal.HistoryRecords;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.metrics.MetricsRecords;
import dev.langchain4j.model.chat.response.StreamingHandle;
import dev.langchain4j.service.TokenStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.Function;
import lombok.extern.slf4j.Slf4j;

/** Owns one session's message queue, stream lifecycle and event sinks. */
@Slf4j
final class LlmRunLoop {
    private LlmClient client;
    private final Function<LlmClient, ServiceResult<LlmClient>> clientUpdater;
    private final Executor runLoopsExecutor;
    private final ChatHistory chatHistory;
    private final MetricsRecorder metricsRecorder;
    private final Queue<MessageToLLM> messageQueue = new ConcurrentLinkedQueue<>();
    private final Map<UUID, EventSink> eventSinks = new ConcurrentHashMap<>();

    private boolean tokenStreamCancelled = false;
    private boolean tokenStreamFinished = false;
    private CompletableFuture<Void> tokenStreamCancellation;
    private MessageToLLM tokenStreamMessage;
    private StreamingHandle tokenStreamHandle;
    private int round = 1;
    private int toolRound = 1;
    private UUID messageId;
    private long loopGeneration;
    private final List<String> stoppedContext = new ArrayList<>();

    private State state = State.IDLE;
    private com.mindrails.backend.userstatus.UserSessionStatusService.Observer statusObserver;
    private com.mindrails.backend.userstatus.UserSessionStatusService.Finished lastFinished;

    synchronized void observe(com.mindrails.backend.userstatus.UserSessionStatusService.Observer observer) {
        statusObserver = observer;
        publishStatus();
    }

    private void publishStatus() {
        if (statusObserver == null) return;
        UUID active = state == State.RUNNING && tokenStreamMessage != null && !tokenStreamFinished && !tokenStreamCancelled
                ? tokenStreamMessage.requestId() : null;
        try { statusObserver.changed(state.name().toLowerCase(java.util.Locale.ROOT), active, messageQueue.size(), lastFinished); }
        catch (RuntimeException error) { log.warn("Cannot publish Session status", error); }
    }

    LlmRunLoop(LlmClient client, Executor runLoopsExecutor, ChatHistory chatHistory, MetricsRecorder metricsRecorder) {
        this(client, current -> ServiceResult.success(current), runLoopsExecutor, chatHistory, metricsRecorder);
    }

    LlmRunLoop(LlmClient client, Function<LlmClient, ServiceResult<LlmClient>> clientUpdater, Executor runLoopsExecutor,
            ChatHistory chatHistory, MetricsRecorder metricsRecorder) {
        this.client = client;
        this.clientUpdater = clientUpdater;
        this.runLoopsExecutor = runLoopsExecutor;
        this.chatHistory = chatHistory;
        this.metricsRecorder = metricsRecorder;
    }

    public synchronized ServiceResult<Void> enqueue(MessageToLLM message) {
        if (state == State.DOOMED) {
            return ServiceResult.failure(ErrorCode.CONFLICT, "Session is doomed");
        }
        messageQueue.add(message);
        if (state == State.IDLE) {
            state = State.RUNNING;
            try {
                long generation = ++loopGeneration;
                runLoopsExecutor.execute(ResourceAccess.capture(() -> runAgentLoop(generation)));
            } catch (RejectedExecutionException error) {
                messageQueue.clear();
                state = State.DOOMED;
                publishStatus();
                return ServiceResult.failure(ErrorCode.UNAVAILABLE, "Session loop executor is unavailable");
            }
        }
        if (message.parameters().containsKey(LlmClient.COMMUNICATION_SOURCE)) emit(message.inputEvent());
        publishStatus();
        return ServiceResult.success();
    }

    public synchronized ServiceResult<Void> stop() {
        if (state != State.RUNNING) return ServiceResult.success();
        loopGeneration++;
        tokenStreamCancelled = true;
        tokenStreamFinished = true;
        var cancellation = tokenStreamCancellation;
        var message = tokenStreamMessage;
        var handle = tokenStreamHandle;
        var queuedMessages = new ArrayList<MessageToLLM>();
        MessageToLLM queued;
        while ((queued = messageQueue.poll()) != null) {
            stoppedContext.add(queued.modelMessage());
            queuedMessages.add(queued);
        }
        stoppedContext.add("[Session event] The user stopped the previous execution. Any queued messages above were not executed. "
                + "Use them only if relevant to the user's next message.");
        state = State.IDLE;
        boolean active = cancellation != null && !cancellation.isDone();
        if (active) cancellation.complete(null);
        if (handle != null && active) cancelHandle(message, handle);
        if (active) {
            lastFinished = new com.mindrails.backend.userstatus.UserSessionStatusService.Finished(message.requestId(), "cancelled");
            var reason = CancelReason.USER_CANCELLED;
            chatHistory.record(HistoryRecords.cancelled(message.parameters(), reason.name()));
            metricsRecorder.record(MetricsRecords.finish(message.parameters(), "CANCELLED", reason.name(), null, null));
            emit(new ChatStreamEvent.Cancelled(message.requestId(), reason));
        }
        queuedMessages.forEach(pending -> emit(new ChatStreamEvent.Cancelled(
                pending.requestId(), CancelReason.USER_CANCELLED)));
        publishStatus();
        return ServiceResult.success();
    }

    public synchronized ServiceResult<Void> registerEventSink(EventSink sink) {
        if (!eventSinks.containsValue(sink)) {
            eventSinks.put(UUID.randomUUID(), sink);
            // This monitor also guards dequeue and event emission, so snapshots precede new events.
            if (state == State.RUNNING && tokenStreamMessage != null && !tokenStreamFinished) {
                send(sink, new ChatStreamEvent.Started(tokenStreamMessage.requestId(), tokenStreamMessage.inputEvent()));
            }
            for (var message : messageQueue) send(sink, message.inputEvent());
        }
        return ServiceResult.success();
    }

    public ServiceResult<Void> unregisterEventSink(EventSink sink) {
        eventSinks.values().remove(sink);
        return ServiceResult.success();
    }
    synchronized <T> ServiceResult<T> withIdleSession(
            Function<List<String>, ServiceResult<T>> snapshot) {
        if (state != State.IDLE || !messageQueue.isEmpty()) {
            return ServiceResult.failure(ErrorCode.CONFLICT,
                    "Only an idle Session can be forked");
        }
        return snapshot.apply(List.copyOf(stoppedContext));
    }

    private enum State {
        IDLE,
        RUNNING,
        DOOMED
    }

    private void runAgentLoop(long generation) {
        while (true) {
            MessageToLLM message = null;
            CompletableFuture<Void> cancellation;
            List<String> context;
            LlmClient runClient;
            try {
                synchronized (this) {
                    if (generation != loopGeneration || state != State.RUNNING) return;
                    message = messageQueue.poll();
                    if (message == null) {
                        state = State.IDLE;
                        publishStatus();
                        return;
                    }
                    tokenStreamMessage = message;
                    tokenStreamCancellation = cancellation = new CompletableFuture<>();
                    tokenStreamCancelled = false;
                    tokenStreamFinished = false;
                    tokenStreamHandle = null;
                    round = toolRound = 1;
                    messageId = UUID.randomUUID();
                    context = List.copyOf(stoppedContext);
                    stoppedContext.clear();
                    message.parameters().put(LlmClient.CANCELLATION, cancellation);
                    message.parameters().put(LlmClient.TOOL_SOURCES, new ConcurrentHashMap<String, Map<String, Object>>());
                    message.parameters().put(LlmClient.COMMUNICATION_CALLS, new com.mindrails.backend.agent.service.SessionCommunicationService.Calls());
                    chatHistory.record(HistoryRecords.start(message.parameters(), message.userMessage()));
                    metricsRecorder.record(MetricsRecords.start(message.parameters()));
                    emit(new ChatStreamEvent.Started(message.requestId(), message.inputEvent()));
                    publishStatus();
                    var result = clientUpdater.apply(client);
                    if (result instanceof ServiceResult.Failure<LlmClient> failure) {
                        fail(message, new ChatStreamEvent.Error(failure.code(), failure.message()), false);
                        return;
                    }
                    client = ((ServiceResult.Success<LlmClient>) result).value();
                    runClient = client;
                }
                if (!context.isEmpty()) {
                    // Factory clients have one fixed memory, exposed by LC4J under its default key.
                    var memory = runClient.getChatMemory(dev.langchain4j.service.memory.ChatMemoryService.DEFAULT);
                    if (memory == null) throw new AssertionError("Client chat memory is unavailable");
                    context.forEach(text -> memory.add(dev.langchain4j.data.message.UserMessage.from(text)));
                }
                if (!cancellation.isDone()) {
                    TokenStream tokenStream = runClient.chatWithStream(message.memoryId(), message.modelMessage(), message.parameters());
                    onTokenStreamEvent(message, tokenStream);
                    if (!cancellation.isDone()) tokenStream.start();
                }
                cancellation.get();
            } catch (InterruptedException error) {
                fail(message, error);
                Thread.currentThread().interrupt();
                return;
            } catch (ExecutionException error) {
                fail(message, error.getCause());
            } catch (RuntimeException | Error error) {
                fail(message, error);
            }
        }
    }

    private synchronized void recordHandle(MessageToLLM message, StreamingHandle handle) {
        if (tokenStreamMessage != message || tokenStreamCancelled || tokenStreamFinished) {
            cancelHandle(message, handle);
        } else {
            tokenStreamHandle = handle;
        }
    }

    private void cancelHandle(MessageToLLM message, StreamingHandle handle) {
        try { handle.cancel(); }
        catch (RuntimeException error) { log.error("Cannot cancel model stream: requestId={}", message.requestId(), error); }
    }

    private void onTokenStreamEvent(MessageToLLM message, TokenStream tokenStream) {
        CompletableFuture<Void> cancellation = message.parameters().get(LlmClient.CANCELLATION);
        tokenStream
                .onRetrieved(contents -> {
                    synchronized (this) {
                        if (isTokenStreamInactive(message, "Retrieved content") || contents.isEmpty()) return;
                        ChatStreamEvent.Retrieved event = toChatStreamEventRetrieved(message, contents);
                        emit(event);
                    }
                })
                .onPartialResponseWithContext((response, context) -> {
                    synchronized (this) {
                        this.recordHandle(message, context.streamingHandle());
                        if (isTokenStreamInactive(message, "Answer delta")) return;
                        metricsRecorder.record(MetricsRecords.firstResponse(message.parameters(), java.time.Instant.now()));
                        chatHistory.record(HistoryRecords.partial(message.parameters(), "answer", response.text()));
                        ChatStreamEvent.AnswerDelta event = toChatEventAnswerDelta(message, round, messageId, response);
                        emit(event);
                    }
                })
                .onPartialThinkingWithContext((thinking, context) -> {
                    synchronized (this) {
                        this.recordHandle(message, context.streamingHandle());
                        if (isTokenStreamInactive(message, "Thinking delta")) return;
                        metricsRecorder.record(MetricsRecords.firstResponse(message.parameters(), java.time.Instant.now()));
                        chatHistory.record(HistoryRecords.partial(message.parameters(), "thinking", thinking.text()));
                        ChatStreamEvent.ThinkingDelta event = toChatEventThinkingDelta(message, round, messageId, thinking);
                        emit(event);
                    }
                })
                .onPartialToolCall(tool -> {
                    synchronized (this) {
                        if (isTokenStreamInactive(message, "Tool arguments delta")) return;
                        if (tool.id() == null || tool.name() == null) return;
                        ChatStreamEvent.ToolArgumentsDelta event = toChatEventToolArgumentsDelta(message, round, tool);
                        emit(event);
                    }
                })
                .onIntermediateResponse(response -> {
                    synchronized (this) {
                        if (isTokenStreamInactive(message, "Intermediate response")) return;
                        ChatStreamEvent.ModelResponseCompleted event = toChatEventModelResponseCompleted(message, round, messageId, response);
                        emit(event);
                        toolRound = round++;
                        messageId = UUID.randomUUID();
                    }
                })
                .beforeToolExecution(execution -> {
                    synchronized (this) {
                        if (isTokenStreamInactive(message, "Tool start")) return;
                        ChatStreamEvent.ToolStarted event = toChatEventToolStarted(message, toolRound, execution);
                        emit(event);
                    }
                })
                .onToolExecuted(execution -> {
                    synchronized (this) {
                        if (isTokenStreamInactive(message, "Tool result")) return;
                        ChatStreamEvent event = toChatEventToolExecuted(message, toolRound, execution);
                        emit(event);
                    }
                })
                .onCompleteResponse(response -> {
                    synchronized (this) {
                        if (isTokenStreamInactive(message, "Complete response")) return;
                        tokenStreamFinished = true;
                        lastFinished = new com.mindrails.backend.userstatus.UserSessionStatusService.Finished(message.requestId(), "completed");
                        publishStatus();
                        try {
                            chatHistory.record(HistoryRecords.completed(message.parameters(), response));
                            metricsRecorder.record(MetricsRecords.finish(
                                    message.parameters(), "SUCCEEDED", null, null, response.tokenUsage()));
                            emit(toChatEventCompleted(message, messageId, response));
                        } finally {
                            cancellation.complete(null);
                        }
                    }
                })
                .onError(error -> fail(message, error));
    }

    private synchronized void fail(MessageToLLM message, Throwable error) {
        if (message == null || isTokenStreamInactive(message, "Stream error")) return;
        log.error("Session stream failed: requestId={}", message.requestId(),
                ExceptionDiagnostics.withoutImagePayload(error));
        fail(message, toChatEventFailed(message, error).error(), error instanceof Error);
    }

    private synchronized void fail(MessageToLLM message, ChatStreamEvent.Error failure, boolean doomed) {
        tokenStreamFinished = true;
        var cancellation = tokenStreamCancellation;
        var pending = new ArrayList<>(messageQueue);
        messageQueue.clear();
        state = doomed ? State.DOOMED : State.IDLE;
        lastFinished = new com.mindrails.backend.userstatus.UserSessionStatusService.Finished(message.requestId(), "failed");
        publishStatus();
        try {
            chatHistory.record(HistoryRecords.failed(message.parameters(), failure.code().name(),
                    failure.message(), failure.reason(), failure.detail()));
            metricsRecorder.record(MetricsRecords.finish(
                    message.parameters(), "FAILED", "STREAM_FAILED", failure.code().name(), null));
            emit(new ChatStreamEvent.Failed(message.requestId(), failure));
            pending.forEach(queued -> emit(new ChatStreamEvent.Failed(queued.requestId(), failure)));
        } finally {
            cancellation.complete(null);
        }
    }

    private boolean isTokenStreamInactive(MessageToLLM message, String eventName) {
        if (tokenStreamMessage != message || tokenStreamCancelled || tokenStreamFinished) {
            log.error("{} received after stream cancellation or completion: requestId={}, cancelled={}, finished={}",
                    eventName, message.requestId(), tokenStreamCancelled, tokenStreamFinished);
            return true;
        }
        return false;
    }

    private void emit(ChatStreamEvent event) {
        eventSinks.values().forEach(sink -> send(sink, event));
    }

    private void send(EventSink sink, ChatStreamEvent event) {
        try { sink.emit(event); }
        catch (RuntimeException error) { log.error("Session event sink failed: requestId={}", event.requestId(), error); }
    }

}
