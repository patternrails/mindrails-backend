package com.mindrails.backend.userstatus;

import com.mindrails.backend.identity.Identity;
import org.junit.jupiter.api.Test;
import org.springframework.web.socket.*;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class UserStatusSlowConsumerTest {
    @Test void blockedSocketCannotBlockPublishingAndOverflowClosesOnlyThatConnection() throws Exception {
        var statuses = new UserSessionStatusService();
        var scheduler = Executors.newSingleThreadScheduledExecutor();
        var handler = new UserStatusWebSocketHandler(statuses, tools.jackson.databind.json.JsonMapper.builder().build(), scheduler);
        var socket = mock(WebSocketSession.class);
        UUID owner = UUID.randomUUID();
        when(socket.getId()).thenReturn("slow"); when(socket.isOpen()).thenReturn(true);
        when(socket.getPrincipal()).thenReturn(new Identity.Current(owner, "user", Identity.Role.Normal, UUID.randomUUID()));
        var sending = new CountDownLatch(1); var blocked = new CountDownLatch(1); var closed = new CountDownLatch(1);
        doAnswer(call -> { sending.countDown(); blocked.await(5, TimeUnit.SECONDS); return null; }).when(socket).sendMessage(any());
        doAnswer(call -> { closed.countDown(); return null; }).when(socket).close(any());
        try {
            handler.afterConnectionEstablished(socket); assertTrue(sending.await(3, TimeUnit.SECONDS));
            var registration = statuses.register(owner, UUID.randomUUID(), UUID.randomUUID());
            assertTimeoutPreemptively(Duration.ofSeconds(1), () -> {
                for (int i = 0; i < 1000; i++) registration.changed("running", null, i, null);
            });
            assertTrue(closed.await(3, TimeUnit.SECONDS));
            verify(socket).close(argThat(reason -> reason.getCode() == 1013));
            var events = new java.util.ArrayList<UserSessionStatusService.Event>(); statuses.subscribe(owner, events::add);
            var status = ((UserSessionStatusService.Snapshot) events.getFirst().data()).sessions().getFirst();
            assertEquals(999, status.queuedCount());
        } finally { blocked.countDown(); handler.shutdown(); scheduler.shutdownNow(); }
    }
}
