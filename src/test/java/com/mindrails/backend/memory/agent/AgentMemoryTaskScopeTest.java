package com.mindrails.backend.memory.agent;

import static com.mindrails.backend.memory.agent.AgentMemoryTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.InMemoryTaskRegistry;
import com.mindrails.backend.tool.ToolFailure;
import com.mindrails.backend.tool.spec.AgentMemoryTools;
import dev.langchain4j.invocation.InvocationContext;
import java.time.Instant;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

class AgentMemoryTaskScopeTest {
    @Test void taskCanUseAgentMemoryWithoutCreatingASessionAndScopeIsRechecked() {
        var f=new Fixture();UUID owner=f.agent();var tasks=new InMemoryTaskRegistry();
        var task=Task.builder().id(UUID.randomUUID()).parentAgentId(owner).provider(f.provider).instructions("Remember").createdAt(Instant.now()).build();tasks.insertIfAbsent(task);
        ReflectionTestUtils.setField(f.memory,"taskRegistry",tasks);
        var parameters=LlmClient.parameters(task,UUID.randomUUID(),UUID.randomUUID(),Instant.now(),"REST");
        var context=InvocationContext.builder().invocationParameters(parameters).build();var tools=new AgentMemoryTools(f.memory);
        assertEquals("CREATED",tools.create("project.database",AgentMemoryEntry.Type.FACT,"Uses MySQL",List.of(),context).status());
        assertEquals("Uses MySQL",tools.get("project.database",context).memories().getFirst().content());
        assertNull(tools.get("project.database",context).memories().getFirst().sourceSessionId());
        assertFalse(f.memory.forTask(task.getId(),owner,"database").isEmpty());
        assertTrue(f.sessionRegistry.listManaged(owner,0,20).isEmpty());
        parameters.put(LlmClient.AGENT_ID,f.agent());assertThrows(ToolFailure.class,() -> tools.get("project.database",context));
        parameters.put(LlmClient.AGENT_ID,owner);tasks.remove(task.getId());assertThrows(ToolFailure.class,() -> tools.get("project.database",context));
    }
}
