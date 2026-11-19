package com.mindrails.backend.agent.registry;

import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.tool.SessionListTool;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.tool.ToolFailure;
import dev.langchain4j.invocation.InvocationContext;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SessionListToolTest {
    @Test void discoversOnlyOwnedOrdinarySessionsAcrossAgentsWithBoundedPages() {
        var sessions = new InMemorySessionRegistry();
        var agents = mock(AgentRegistry.class);
        UUID owner = UUID.randomUUID();
        var source = session(owner, "source", Instant.now());
        var rust = session(owner, "Rust ownership", Instant.now());
        var rust2 = session(owner, "Learning RUST", Instant.now().minusSeconds(10));
        var foreign = session(UUID.randomUUID(), "Rust private", Instant.now());
        for (var s : new Session[]{source,rust,rust2,foreign}) sessions.insertIfAbsent(s);
        var parameters = LlmClient.parameters(source, UUID.randomUUID(), "WEBSOCKET");
        var context = InvocationContext.builder().invocationParameters(parameters).build();
        var tool = new SessionListTool(sessions, agents);
        var first = tool.list("rust", 0, 1, context);
        assertEquals(source.getId(), first.currentSessionId());
        assertEquals(rust.getId(), first.sessions().getFirst().sessionId());
        assertEquals(1, first.nextOffset());
        var second = tool.list("rust", first.nextOffset(), 1, context);
        assertEquals(rust2.getId(), second.sessions().getFirst().sessionId());
        assertNull(second.nextOffset());
        assertEquals(2, tool.list(null, null, null, context).sessions().size());
        assertNull(ResourceAccess.userId());
        assertThrows(ToolFailure.class, () -> tool.list(null, -1, 1, context));
        parameters.put(LlmClient.TASK_ID, UUID.randomUUID());
        assertThrows(ToolFailure.class, () -> tool.list(null, null, null, context));
    }
    private Session session(UUID owner, String name, Instant updated) {
        return Session.builder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID()).ownerId(owner)
                .memoryId(UUID.randomUUID()).name(name).createdAt(updated).updatedAt(updated).build();
    }
}
