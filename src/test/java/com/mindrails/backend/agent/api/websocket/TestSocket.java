package com.mindrails.backend.agent.api.websocket;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway.AgentSessionScope;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class TestSocket {
    final WebSocketSession session = mock(WebSocketSession.class);
    final AgentSessionScope agentSessionScope = new AgentSessionScope(UUID.randomUUID(), UUID.randomUUID());
    final List<JsonNode> messages = new CopyOnWriteArrayList<>();
    final List<PingMessage> pings = new CopyOnWriteArrayList<>();
    final AtomicBoolean open = new AtomicBoolean(true);
    final CountDownLatch entered = new CountDownLatch(1);
    final CountDownLatch release = new CountDownLatch(1);
    volatile String blockedType;
    volatile JsonNode blockedMessage;
    volatile CloseStatus closeStatus;
    private final JsonMapper mapper = JsonMapper.builder().build();

    TestSocket() throws Exception {
        when(session.getId()).thenReturn(UUID.randomUUID().toString());
        when(session.isOpen()).thenAnswer(invocation -> open.get());
        when(session.getAttributes()).thenReturn(Map.of(
                AgentSessionHandshakeInterceptor.SCOPE_ATTRIBUTE, agentSessionScope));
        doAnswer(invocation -> {
            WebSocketMessage<?> message = invocation.getArgument(0);
            if (message instanceof TextMessage text) {
                JsonNode json = mapper.readTree(text.getPayload());
                if (json.path("type").asString().equals(blockedType)) {
                    blockedMessage = json;
                    entered.countDown();
                    release.await(5, TimeUnit.SECONDS);
                }
                if (open.get()) messages.add(json);
            } else if (message instanceof PingMessage ping) pings.add(ping);
            return null;
        }).when(session).sendMessage(any());
        doAnswer(invocation -> {
            closeStatus = invocation.getArgument(0);
            open.set(false);
            release.countDown();
            return null;
        }).when(session).close(any(CloseStatus.class));
    }

    List<String> types() { return messages.stream().map(m -> m.path("type").asString()).toList(); }
    JsonNode last(String type) { return messages.stream().filter(m -> m.path("type").asString().equals(type)).reduce((a, b) -> b).orElseThrow(); }

    static void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(5);
        assertTrue(condition.getAsBoolean(), "Timed out waiting for asynchronous transport");
    }
}
