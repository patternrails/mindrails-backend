package com.mindrails.backend.agent.service.internal;

import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.EventSink;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.metrics.MetricsRecorder;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.extension.AfterEachCallback;
import org.junit.jupiter.api.extension.ExtensionContext;

/** Exercises the real Session loop and waits explicitly for asynchronous observations in tests. */
public final class TestSessionChat implements AfterEachCallback {
    private static final ThreadLocal<List<DefaultLoopSessionStreamService>> SERVICES = ThreadLocal.withInitial(ArrayList::new);

    public static DefaultLoopSessionStreamService service(SessionRegistry sessions, LlmClientFactory factory,
            ChatHistory history, MetricsRecorder metrics) {
        var service = new DefaultLoopSessionStreamService(sessions, factory, history, metrics, null);
        SERVICES.get().add(service);
        return service;
    }

    public static ServiceResult<Void> start(LoopSessionStreamService service, UUID id, ClientCommand.Chat command, EventSink sink) {
        var bound = new java.util.concurrent.atomic.AtomicReference<EventSink>();
        bound.set(event -> {
            if (!command.requestId().equals(event.requestId())) return;
            sink.emit(event);
            if (event instanceof ChatStreamEvent.Completed || event instanceof ChatStreamEvent.Failed || event instanceof ChatStreamEvent.Cancelled)
                service.unbind(id, bound.get());
        });
        return service.bind(id, bound.get()).flatMap(ignored -> service.enqueue(id, command))
                .onFailure(failure -> service.unbind(id, bound.get()));
    }

    public static ServiceResult<ChatStreamEvent.Completed> chat(LoopSessionStreamService service, UUID id, String text) {
        return chat(service, id, text, UUID.randomUUID());
    }

    public static ServiceResult<ChatStreamEvent.Completed> chat(LoopSessionStreamService service, UUID id, String text, UUID requestId) {
        var terminal = new CompletableFuture<ChatStreamEvent>();
        EventSink sink = event -> {
            if (requestId.equals(event.requestId()) && (event instanceof ChatStreamEvent.Completed
                    || event instanceof ChatStreamEvent.Failed || event instanceof ChatStreamEvent.Cancelled)) terminal.complete(event);
        };
        try {
            var result = service.bind(id, sink)
                    .flatMap(ignored -> service.enqueue(id, new ClientCommand.Chat(requestId, text)));
            if (result instanceof ServiceResult.Failure<Void> failure) return ServiceResult.failure(failure.code(), failure.message());
            var event = terminal.get(5, TimeUnit.SECONDS);
            if (event instanceof ChatStreamEvent.Failed failed)
                return ServiceResult.failure(failed.error().code(), failed.error().message());
            return ServiceResult.success(assertInstanceOf(ChatStreamEvent.Completed.class, event));
        } catch (Exception error) { throw new AssertionError("Session did not finish", error); }
        finally { service.unbind(id, sink); }
    }

    public static void await(BooleanSupplier condition) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean()) {
            if (System.nanoTime() >= deadline) fail("Session observation timed out");
            try { Thread.sleep(5); }
            catch (InterruptedException error) { Thread.currentThread().interrupt(); throw new AssertionError(error); }
        }
    }

    public static void awaitTerminal(List<ChatStreamEvent> events) {
        await(() -> events.stream().anyMatch(event -> event instanceof ChatStreamEvent.Completed
                || event instanceof ChatStreamEvent.Failed || event instanceof ChatStreamEvent.Cancelled));
    }

    @Override public void afterEach(ExtensionContext context) {
        SERVICES.get().forEach(DefaultLoopSessionStreamService::shutdown);
        SERVICES.remove();
    }
}
