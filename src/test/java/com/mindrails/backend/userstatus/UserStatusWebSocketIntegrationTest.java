package com.mindrails.backend.userstatus;

import com.mindrails.backend.identity.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import java.net.URI;
import java.net.http.*;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(classes = UserStatusWebSocketIntegrationTest.App.class, webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"mindrails.identity.admin-password=test-admin-password-2026",
        "mindrails.identity.secure-cookie=false"})
class UserStatusWebSocketIntegrationTest {
    @Configuration(proxyBeanMethods = false) @EnableAutoConfiguration @EnableWebSocket
    @Import({UserStatusWebSocketConfiguration.class, UserSessionStatusService.class, TestIdentityConfiguration.class})
    static class App {}
    @Autowired ApplicationContext context;
    @Autowired UserSessionStatusService statuses;
    @Autowired IdentityService identity;

    @Test void authenticatesWithSingleUseTicketStreamsOnlyOwnerStatusAndRejectsCommands() throws Exception {
        var actor = identity.authenticate(identity.login("admin", "test-admin-password-2026", "status-test").accessToken());
        String path = UserStatusWebSocketConfiguration.PATH;
        URI uri = URI.create("ws://localhost:" + context.getEnvironment().getProperty("local.server.port") + path);
        var registration = statuses.register(actor.id(), UUID.randomUUID(), UUID.randomUUID());
        registration.changed("running", UUID.randomUUID(), 1, null);
        var foreign = statuses.register(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        foreign.changed("running", UUID.randomUUID(), 5, null);
        String ticket = identity.ticket(actor, path);
        var frames = new Frames();
        try (var client = HttpClient.newHttpClient()) {
            var denied = assertThrows(ExecutionException.class, () -> client.newWebSocketBuilder().buildAsync(uri, new Frames()).get(3, TimeUnit.SECONDS));
            assertEquals(401, assertInstanceOf(WebSocketHandshakeException.class, denied.getCause()).getResponse().statusCode());
            var socket = client.newWebSocketBuilder().buildAsync(URI.create(uri + "?ticket=" + ticket), frames).get(3, TimeUnit.SECONDS);
            assertEquals(1, frames.next().path("data").path("sessions").size());
            foreign.changed("idle", null, 0, null);
            registration.changed("idle", null, 0, new UserSessionStatusService.Finished(UUID.randomUUID(), "completed"));
            var event = frames.next();
            assertEquals("user.session.status", event.path("type").asString());
            assertEquals("completed", event.path("data").path("lastFinished").path("outcome").asString());
            assertFalse(event.path("data").has("message")); assertFalse(event.path("data").has("answer"));
            var replay = assertThrows(ExecutionException.class, () -> client.newWebSocketBuilder()
                    .buildAsync(URI.create(uri + "?ticket=" + ticket), new Frames()).get(3, TimeUnit.SECONDS));
            assertEquals(401, assertInstanceOf(WebSocketHandshakeException.class, replay.getCause()).getResponse().statusCode());
            socket.sendText("{\"type\":\"chat\"}", true).get(3, TimeUnit.SECONDS);
            assertEquals(1008, frames.closed.get(3, TimeUnit.SECONDS));
        }
    }
    private static final class Frames implements WebSocket.Listener {
        final LinkedBlockingQueue<String> messages = new LinkedBlockingQueue<>();
        final CompletableFuture<Integer> closed = new CompletableFuture<>();
        final StringBuilder buffer = new StringBuilder();
        @Override public void onOpen(WebSocket socket) { socket.request(1); }
        @Override public CompletionStage<?> onText(WebSocket socket, CharSequence data, boolean last) {
            buffer.append(data); if (last) { messages.add(buffer.toString()); buffer.setLength(0); }
            socket.request(1); return CompletableFuture.completedFuture(null);
        }
        @Override public CompletionStage<?> onClose(WebSocket socket, int code, String reason) { closed.complete(code); return CompletableFuture.completedFuture(null); }
        JsonNode next() throws Exception { String value = messages.poll(3, TimeUnit.SECONDS); assertNotNull(value); return JsonMapper.builder().build().readTree(value); }
    }
}
