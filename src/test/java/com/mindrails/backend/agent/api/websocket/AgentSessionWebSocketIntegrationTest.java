package com.mindrails.backend.agent.api.websocket;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.identity.*;

import com.mindrails.backend.agent.api.websocket.protocol.ChatStreamEvent;
import com.mindrails.backend.agent.api.websocket.protocol.ClientCommand;
import com.mindrails.backend.agent.service.AgentSessionChatStreamGateway;
import com.mindrails.backend.common.ServiceResult;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Actual HTTP upgrade and JSON frames over embedded Tomcat, with a scripted application boundary. */
@SpringBootTest(classes = AgentSessionWebSocketIntegrationTest.TestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"mindrails.identity.admin-password=test-admin-password-2026",
        "mindrails.identity.secure-cookie=false"})
class AgentSessionWebSocketIntegrationTest {
    static final UUID AGENT = UUID.randomUUID(), SESSION = UUID.randomUUID();
    @Autowired ApplicationContext context;
    String accessToken() {
        return context.getBean(IdentityService.class).login("admin","test-admin-password-2026","test").accessToken();
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @Import({AgentSessionWebSocketConfiguration.class,TestIdentityConfiguration.class})
    static class TestApplication {
        @Bean
        AgentSessionChatStreamGateway scriptedGateway() {
            return new AgentSessionChatStreamGateway() {
                @Override public ServiceResult<SessionSnapshot> validate(AgentSessionScope agentSessionScope) {
                    return agentSessionScope.agentId().equals(AGENT) && agentSessionScope.sessionId().equals(SESSION)
                            ? ServiceResult.success(new SessionSnapshot(null))
                            : ServiceResult.failure(ServiceResult.ErrorCode.NOT_FOUND, "Session not found");
                }
                private final java.util.Map<AgentSessionScope, java.util.Set<EventSink>> sinks = new java.util.concurrent.ConcurrentHashMap<>();
                @Override public ServiceResult<Void> chat(AgentSessionScope agentSessionScope, ClientCommand.Chat command) {
                    if (validate(agentSessionScope) instanceof ServiceResult.Failure<SessionSnapshot> failure)
                        return ServiceResult.failure(failure.code(), failure.message());
                    EventSink sink = event -> sinks.getOrDefault(agentSessionScope, java.util.Set.of()).forEach(target -> target.emit(event));
                    UUID message = UUID.randomUUID();
                    sink.emit(new ChatStreamEvent.Started(command.requestId()));
                    sink.emit(new ChatStreamEvent.AnswerDelta(command.requestId(), 1, message, command.message()));
                    sink.emit(new ChatStreamEvent.Completed(command.requestId(), message, command.message(), "STOP", null));
                    return ServiceResult.success();
                }
                @Override public ServiceResult<Void> command(AgentSessionScope scope, CommandType type, Object payload) {
                    if (type == CommandType.UNBIND) {
                        sinks.getOrDefault(scope, java.util.concurrent.ConcurrentHashMap.newKeySet()).remove(payload);
                        return ServiceResult.success();
                    }
                    return validate(scope).map(ignored -> {
                        if (type == CommandType.BIND) sinks.computeIfAbsent(scope, id -> java.util.concurrent.ConcurrentHashMap.newKeySet()).add((EventSink) payload);
                        return null;
                    });
                }
            };
        }
    }

    @Test
    void upgradesAndStreamsTwoChatsOverTheSameConnection() throws Exception {
        assertEquals(1, context.getBeansOfType(AgentSessionChatStreamGateway.class).size());
        Frames listener = new Frames();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket ws = client.newWebSocketBuilder().header("Authorization","Bearer "+accessToken()).buildAsync(uri(AGENT, SESSION), listener).get(3, TimeUnit.SECONDS);
            try {
                assertEquals("connection.ready", listener.next().path("type").asString());
                long sequence = 1;
                for (String text : List.of("你好", "再来一轮")) {
                    UUID requestId = UUID.randomUUID();
                    String json = JsonMapper.builder().build().writeValueAsString(java.util.Map.of("version", 1, "type", "chat",
                            "requestId", requestId, "data", java.util.Map.of("message", text)));
                    ws.sendText(json, true).get(3, TimeUnit.SECONDS);
                    List<String> types = new ArrayList<>();
                    JsonNode frame = null;
                    for (int index = 0; index < 4; index++) {
                        frame = listener.next();
                        types.add(frame.path("type").asString());
                        assertEquals(++sequence, frame.path("seq").longValue());
                        assertEquals(requestId.toString(), frame.path("requestId").asString());
                        if (frame.path("type").asString().equals("run.completed")) {
                            assertEquals(text, frame.path("data").path("answer").asString());
                        }
                        if (frame.path("type").asString().equals("chat.accepted")) {
                            assertFalse(frame.has("runId"));
                        }
                    }
                    assertEquals(List.of("run.started", "answer.delta", "run.completed", "chat.accepted"), types);
                }
            } finally { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS); }
        }
    }

    @Test
    void cancellationIsAcknowledgedOverTheWebSocket() throws Exception {
        Frames listener = new Frames();
        try (HttpClient client = HttpClient.newHttpClient()) {
            WebSocket ws = client.newWebSocketBuilder().header("Authorization","Bearer "+accessToken()).buildAsync(uri(AGENT, SESSION), listener).get(3, TimeUnit.SECONDS);
            try {
                assertEquals("connection.ready", listener.next().path("type").asString());
                UUID requestId = UUID.randomUUID();
                String json = JsonMapper.builder().build().writeValueAsString(java.util.Map.of("version", 1,
                        "type", "cancel", "requestId", requestId,
                        "data", java.util.Map.of()));
                ws.sendText(json, true).get(3, TimeUnit.SECONDS);
                JsonNode reply = listener.next();
                assertEquals("run.cancelling", reply.path("type").asString());
                assertEquals(requestId.toString(), reply.path("requestId").asString());
                assertEquals("CANCELLING", reply.path("data").path("state").asString());
                assertEquals(2, reply.path("seq").longValue());
            } finally { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS); }
        }
    }

    @Test
    void validatesMissingSessionsOnChatAndAllowsConfiguredCrossOriginHandshakes() throws Exception {
        try (HttpClient client = HttpClient.newHttpClient()) {
            Frames missingListener = new Frames();
            WebSocket missing = client.newWebSocketBuilder().header("Authorization","Bearer "+accessToken())
                    .buildAsync(uri(AGENT, UUID.randomUUID()), missingListener).get(3, TimeUnit.SECONDS);
            try {
                JsonNode error = missingListener.next();
                assertEquals("command.error", error.path("type").asString());
                assertEquals("NOT_FOUND", error.path("data").path("code").asString());
            } finally { if (!missing.isOutputClosed()) missing.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS); }
            Frames listener = new Frames();
            WebSocket ws = client.newWebSocketBuilder().header("Authorization","Bearer "+accessToken()).header("Origin", "http://localhost:3000")
                    .buildAsync(uri(AGENT, SESSION), listener).get(3, TimeUnit.SECONDS);
            try {
                assertEquals("connection.ready", listener.next().path("type").asString());
            } finally { ws.sendClose(WebSocket.NORMAL_CLOSURE, "done").get(3, TimeUnit.SECONDS); }
        }
    }

    @Test
    void webSocketRequiresAuthenticationAndTicketsAreSingleUse() throws Exception {
        var identity=context.getBean(IdentityService.class);
        var tokens=identity.login("admin","test-admin-password-2026","ticket-test");
        var actor=identity.authenticate(tokens.accessToken());
        try(var client=HttpClient.newHttpClient()) {
            var denied=assertThrows(java.util.concurrent.ExecutionException.class,() ->
                    client.newWebSocketBuilder().buildAsync(uri(AGENT,SESSION),new Frames()).get(3,TimeUnit.SECONDS));
            assertEquals(401,assertInstanceOf(java.net.http.WebSocketHandshakeException.class,denied.getCause()).getResponse().statusCode());
            var crossOrigin=assertThrows(java.util.concurrent.ExecutionException.class,() -> client.newWebSocketBuilder()
                    .header("Authorization","Bearer "+tokens.accessToken()).header("Origin","https://unlisted.example")
                    .buildAsync(uri(AGENT,SESSION),new Frames()).get(3,TimeUnit.SECONDS));
            assertEquals(403,assertInstanceOf(java.net.http.WebSocketHandshakeException.class,crossOrigin.getCause()).getResponse().statusCode());
            String ticket=identity.ticket(actor,uri(AGENT,SESSION).getPath());
            URI target=URI.create(uri(AGENT,SESSION)+"?ticket="+ticket);
            var frames=new Frames();var socket=client.newWebSocketBuilder().header("Origin","http://localhost:3000")
                    .buildAsync(target,frames).get(3,TimeUnit.SECONDS);
            assertEquals("connection.ready",frames.next().path("type").asString());
            var replay=assertThrows(java.util.concurrent.ExecutionException.class,() ->
                    client.newWebSocketBuilder().buildAsync(target,new Frames()).get(3,TimeUnit.SECONDS));
            assertEquals(401,assertInstanceOf(java.net.http.WebSocketHandshakeException.class,replay.getCause()).getResponse().statusCode());
            identity.logout(actor);
            socket.sendText("{\"version\":1,\"type\":\"chat\",\"requestId\":\""+UUID.randomUUID()+"\",\"data\":{\"message\":\"must not run\"}}",true).get(3,TimeUnit.SECONDS);
            assertEquals(1008,frames.closed.get(3,TimeUnit.SECONDS));
        }
    }

    private URI uri(UUID agentId, UUID sessionId) {
        return URI.create("ws://localhost:" + context.getEnvironment().getProperty("local.server.port")
                + AgentSessionHandshakeInterceptorTest.path(agentId, sessionId));
    }

    private static class Frames implements WebSocket.Listener {
        final LinkedBlockingQueue<String> frames = new LinkedBlockingQueue<>();
        final StringBuilder current = new StringBuilder();
        final CompletableFuture<Integer> closed=new CompletableFuture<>();
        @Override public CompletionStage<?> onClose(WebSocket socket,int status,String reason) {closed.complete(status);return CompletableFuture.completedFuture(null);}
        @Override public void onOpen(WebSocket ws) { ws.request(1); }
        @Override public CompletionStage<?> onText(WebSocket ws, CharSequence text, boolean last) {
            current.append(text);
            if (last) { frames.add(current.toString()); current.setLength(0); }
            ws.request(1);
            return CompletableFuture.completedFuture(null);
        }
        JsonNode next() throws Exception {
            String value = frames.poll(3, TimeUnit.SECONDS);
            assertNotNull(value, "Expected a WebSocket frame");
            return JsonMapper.builder().build().readTree(value);
        }
    }
}
