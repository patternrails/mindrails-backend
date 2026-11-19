package com.mindrails.backend.agent.registry;

import static org.junit.jupiter.api.Assertions.*;

import com.mindrails.backend.agent.model.Session;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

class SessionNameTest {
    @Test
    void firstMessageCreatesTitleAndManualNamesWin() {
        var registry = new SessionRegistryConfiguration().sessionRegistry();
        var id = UUID.randomUUID();
        registry.insertIfAbsent(
                Session.builder()
                        .id(id)
                        .parentAgentId(UUID.randomUUID())
                        .memoryId(UUID.randomUUID())
                        .createdAt(Instant.now())
                        .updatedAt(Instant.now())
                        .build());
        registry.nameIfAbsent(id, "  First\nmessage  ");
        assertEquals("First message", registry.findManaged(id).orElseThrow().getName());
        var session = registry.findManaged(id).orElseThrow();
        registry.save(session.toBuilder().name("My title").build());
        registry.nameIfAbsent(id, "Different message");
        assertEquals("My title", registry.findManaged(id).orElseThrow().getName());
    }
}
