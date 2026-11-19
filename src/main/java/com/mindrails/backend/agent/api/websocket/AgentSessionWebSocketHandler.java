package com.mindrails.backend.agent.api.websocket;

import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.AgentSessionScope;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import jakarta.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;
import tools.jackson.databind.json.JsonMapper;

/**
 * Routes WebSocket lifecycle events and client commands to their connection, run and Gateway.
 */
@Slf4j
public final class AgentSessionWebSocketHandler extends TextWebSocketHandler {
    private final AgentSessionChatStreamGateway gateway;
    private final JsonMapper mapper;
    private final ScheduledExecutorService scheduler;
    private final Map<String, AgentWebSocketConnection> connections = new ConcurrentHashMap<>();

    public AgentSessionWebSocketHandler(AgentSessionChatStreamGateway gateway, JsonMapper mapper,
            ScheduledExecutorService scheduler) {
        this.gateway = gateway;
        this.mapper = mapper;
        this.scheduler = scheduler;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession webSocketSession) throws Exception {
        var scope = (AgentSessionScope) webSocketSession.getAttributes()
                .get(AgentSessionHandshakeInterceptor.SCOPE_ATTRIBUTE);
        if (scope == null) {
            webSocketSession.close(CloseStatus.POLICY_VIOLATION);
            return;
        }
        var sender = new OutboundMessageSender(webSocketSession, scope, mapper, scheduler);
        var connection = new AgentWebSocketConnection(webSocketSession, scope, sender, scheduler, gateway);
        connections.put(webSocketSession.getId(), connection);
        connection.open();
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        this.close(session, status);
    }

    @Override
    public void handleTransportError(WebSocketSession session, Throwable exception) {
        log.error("Transport error: sessionId={}", session.getId(), exception);
        this.close(session, CloseStatus.SERVER_ERROR);
    }

    @PreDestroy
    public void shutdown() {
        connections.keySet().forEach(connectionId -> {
            connections.get(connectionId).close(CloseStatus.GOING_AWAY);
            connections.remove(connectionId);
        });
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) {
        AgentWebSocketConnection connection = connections.get(session.getId());
        if (connection == null || connection.isClosed()) {
            log.error("WebSocket connection not found or closed: connectionId={}", session.getId());
            return;
        }
        String payload = message.getPayload();
        if (payload.getBytes(StandardCharsets.UTF_8).length > AgentWebSocketProperties.inboundBytes) {
            this.close(session, CloseStatus.TOO_BIG_TO_PROCESS);
            return;
        }
        ServiceResult.tryOf(() -> StreamProtocol.decode(payload), ErrorCode.INVALID_ARGUMENT)
                .onFailure(failure -> connection.error(StreamProtocol.requestId(payload), failure.code(), failure.message()))
                .peek(command -> {
                    log.info("Chat received: requestId={}, connections={}", command.requestId(), connections);
                    connection.dispatch(command);
                });
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        close(session, CloseStatus.NOT_ACCEPTABLE);
    }

    @Override
    protected void handlePongMessage(WebSocketSession session, PongMessage message) {
        AgentWebSocketConnection connection = connections.get(session.getId());
        if (connection != null) connection.pong(message.getPayload());
    }

    private void close(WebSocketSession session, CloseStatus status) {
        AgentWebSocketConnection connection = connections.get(session.getId());
        if (connection != null) {
            connection.close(status);
            connections.remove(session.getId());
        }
    }
}
