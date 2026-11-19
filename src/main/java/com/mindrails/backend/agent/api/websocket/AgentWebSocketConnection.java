package com.mindrails.backend.agent.api.websocket;

import static com.mindrails.backend.agent.api.websocket.protocol.ServerMessageType.*;

import com.mindrails.backend.agent.api.websocket.OutboundMessageSender.Draft;
import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.api.websocket.protocol.RunState;
import com.mindrails.backend.agent.api.websocket.protocol.StreamPayloads;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.AgentSessionScope;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.CommandType;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.EventSink;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;

import java.nio.ByteBuffer;
import java.util.UUID;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.WebSocketSession;

/**
 * One connection's command routing, heartbeat and event forwarding.
 */
@Slf4j
final class AgentWebSocketConnection {

    private final WebSocketSession webSocketSession;
    private final AgentSessionScope scope;
    private final OutboundMessageSender sender;
    private final ScheduledExecutorService scheduler;
    private final AgentSessionChatStreamGateway gateway;

    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicBoolean bound = new AtomicBoolean();
    private final EventSink eventSink = this::event;
    private final Runnable unbindAction;
    private final List<ChatStreamEvent> openingEvents = new ArrayList<>();
    private boolean ready;

    private volatile ScheduledFuture<?> heartbeat;
    private Long pingNonce;
    private long pingSentAt;
    private long nextPing;

    public AgentWebSocketConnection(WebSocketSession webSocketSession, AgentSessionScope scope, OutboundMessageSender sender,
                                    ScheduledExecutorService scheduler,
                                    AgentSessionChatStreamGateway gateway) {
        this.webSocketSession = webSocketSession;
        this.scope = scope;
        this.sender = sender;
        this.scheduler = scheduler;
        this.gateway = gateway;
        this.unbindAction = ResourceAccess.capture(() -> gateway.command(scope, CommandType.UNBIND, eventSink)
                .onFailure(failure -> log.error("Cannot unbind Session sink: scope={}, error={}", scope, failure.message())));

        this.nextPing = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AgentWebSocketProperties.pingMillis);
    }

    public boolean isClosed() {
        return closed.get();
    }

    public void open() {
        var result = gateway.command(scope, CommandType.BIND, eventSink);
        if (result instanceof ServiceResult.Failure<?> failure) {
            sender.send(Draft.builder().type(COMMAND_ERROR)
                    .data(new StreamPayloads.CommandError(failure.code(), failure.message())).build(),
                    () -> close(CloseStatus.POLICY_VIOLATION));
            return;
        }
        bound.set(true);
        if (closed.get()) { unbind(); return; }
        webSocketSession.setTextMessageSizeLimit(AgentWebSocketProperties.inboundBytes);
        webSocketSession.setBinaryMessageSizeLimit(AgentWebSocketProperties.inboundBytes);
        heartbeat = scheduler.scheduleAtFixedRate(this::heartbeat,
                AgentWebSocketProperties.pingMillis, Math.min(AgentWebSocketProperties.pingMillis, AgentWebSocketProperties.pongTimeoutMillis),
                TimeUnit.MILLISECONDS);
        synchronized (this) {
            if (closed.get()) { heartbeat.cancel(false); unbind(); return; }
            sender.send(Draft.builder().type(CONNECTION_READY).data(new StreamPayloads.Ready(null)).build());
            ready = true;
            openingEvents.forEach(this::event);
            openingEvents.clear();
        }
    }

    public void dispatch(ClientCommand command) {
        log.info("Chat received: requestId={}, connection={}, connectionId={}, scope={}, closed={}",
                command.requestId(), this, this.webSocketSession.getId(), this.scope, this.isClosed());

        var result = switch (command) {
            case ClientCommand.Chat chat -> this.chat(chat);
            case ClientCommand.Cancel cancel -> this.cancel(cancel);
        };
        result.onFailure(failure -> this.reject(command, failure));
    }

    public void reject(ClientCommand command, ServiceResult.Failure<?> failure) {
        sender.send(Draft.builder().type(COMMAND_ERROR).requestId(command.requestId())
                .data(new StreamPayloads.CommandError(failure.code(), failure.message())).build());
    }

    public void error(UUID requestId, ErrorCode code, String message) {
        sender.send(Draft.builder().type(COMMAND_ERROR).requestId(requestId)
                .data(new StreamPayloads.CommandError(code, message)).build());
    }

    public synchronized void pong(ByteBuffer payload) {
        if (payload.remaining() != Long.BYTES || pingNonce == null || payload.getLong(payload.position()) != pingNonce)
            return;
        pingNonce = null;
        pingSentAt = 0;
        nextPing = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(AgentWebSocketProperties.pingMillis);
    }

    public void close(CloseStatus status) {
        if (!closed.compareAndSet(false, true)) return;
        if (heartbeat != null) heartbeat.cancel(false);
        try { unbind(); } finally { sender.close(status); }
    }

    private void unbind() {
        if (bound.compareAndSet(true, false)) unbindAction.run();
    }

    private ServiceResult<Void> chat(ClientCommand.Chat command) {
        return gateway.chat(scope, command).onSuccess(() -> sender.send(
                Draft.builder().type(CHAT_ACCEPTED).requestId(command.requestId())
                        .data(new StreamPayloads.State(RunState.STARTING)).build()));
    }

    private ServiceResult<Void> cancel(ClientCommand.Cancel command) {
        sender.send(Draft.builder().type(RUN_CANCELLING).requestId(command.requestId())
                .data(new StreamPayloads.State(RunState.CANCELLING)).build());
        return gateway.command(scope, CommandType.STOP, null);
    }

    private synchronized void event(ChatStreamEvent event) {
        if (isClosed()) {
            return;
        }
        if (!ready) { openingEvents.add(event); return; }
        StreamProtocol.Event mapped;
        try {
            mapped = StreamProtocol.map(event);
        } catch (RuntimeException exception) {
            log.error("Invalid streaming event", exception);
            return;
        }
        Draft draft = Draft.from(mapped);
        if (event instanceof ChatStreamEvent.Completed && sender.exceedsFrameLimit(draft)) {
            log.error("Final answer exceeds the outbound message limit");
            return;
        }
        if (!sender.send(draft)) log.error("Failed to send draft");
    }

    private synchronized void heartbeat() {
        if (closed.get()) return;
        long now = System.nanoTime();
        if (pingNonce != null) {
            if (pingSentAt != 0 && now - pingSentAt >= TimeUnit.MILLISECONDS.toNanos(AgentWebSocketProperties.pongTimeoutMillis))
                close(new CloseStatus(1001, "Pong timeout"));
            return;
        }
        if (now < nextPing) return;
        pingNonce = now;
        pingSentAt = 0;
        sender.ping(ByteBuffer.allocate(Long.BYTES).putLong(now).array(), () -> pingSent(now));
    }

    private synchronized void pingSent(long nonce) {
        if (pingNonce != null && pingNonce == nonce) pingSentAt = System.nanoTime();
    }
}
