package com.mindrails.backend.agent.service.internal;

import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.agent.service.internal.llm.LlmClientFactory;
import com.mindrails.backend.history.store.InMemoryHistoryStore;
import com.mindrails.backend.memory.ChatMemoryStoreFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** Explicit fork storage for manually constructed service fixtures. */
public final class TestSessionFork {
    private TestSessionFork() {}
    public static DefaultSessionService install(DefaultSessionService service) { return install(service, null, null); }
    public static DefaultSessionService install(DefaultSessionService service, LlmClientFactory factory,
            DefaultLoopSessionStreamService loops) {
        var sessions = (SessionRegistry) ReflectionTestUtils.getField(service, "sessionRegistry");
        var memory = factory == null ? com.mindrails.backend.memory.TestMemory.inMemory(100)
                : (ChatMemoryStoreFactory) ReflectionTestUtils.getField(factory, "chatMemoryStoreFactory");
        ReflectionTestUtils.setField(service, "forks", new SessionForkService(sessions, memory,
                new InMemoryHistoryStore(), loops, null, null, null));
        ReflectionTestUtils.setField(service, "loops", loops);
        return service;
    }
}
