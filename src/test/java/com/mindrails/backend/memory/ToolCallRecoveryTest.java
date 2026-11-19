package com.mindrails.backend.memory;

import static org.junit.jupiter.api.Assertions.*;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.*;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.store.memory.chat.InMemoryChatMemoryStore;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ToolCallRecoveryTest {
    private final UUID memoryId = UUID.randomUUID();
    private final InMemoryChatMemoryStore store = new InMemoryChatMemoryStore();
    private final ToolExecutionRequest first = call("first");
    private final ToolExecutionRequest second = call("second");

    @Test
    void nextUserTurnRepairsAnInterruptedCallBeforeTheNewMessageAndPersistsIt() {
        var oldUser = UserMessage.from("Edit the file");
        var toolCall = AiMessage.from(first);
        store.updateMessages(memoryId, List.of(oldUser, toolCall));
        var memory = memory(100);
        var next = UserMessage.from("Continue");

        ToolCallRecovery.recover(memory);
        memory.add(next);

        var result = assertInstanceOf(ToolExecutionResultMessage.class, memory.messages().get(2));
        assertEquals(first.id(), result.id());
        assertEquals(first.name(), result.toolName());
        assertTrue(result.text().contains("TOOL_RESULT_MISSING"));
        assertTrue(result.text().contains("may or may not have executed"));
        assertEquals(List.of(oldUser, toolCall, result, next), store.getMessages(memoryId));
        // Recreating the window must not introduce a second recovery result.
        var recreated = memory(100);
        ToolCallRecovery.recover(recreated);
        recreated.add(UserMessage.from("Again"));
        assertEquals(1, recreated.messages().stream().filter(ToolExecutionResultMessage.class::isInstance).count());
    }

    @Test
    void preservesSuccessfulParallelResultsAndRepairsEachMissingResultInItsOwnRound() {
        var done = ToolExecutionResultMessage.from(second, "Already done");
        var priorRetry = UserMessage.from("Previous failed retry");
        store.updateMessages(memoryId, List.of(UserMessage.from("Start"), AiMessage.from(first, second),
                done, priorRetry, AiMessage.from(call("third"))));
        var memory = memory(100);

        ToolCallRecovery.recover(memory);
        memory.add(UserMessage.from("Continue"));

        var messages = memory.messages();
        assertSame(done, messages.get(2));
        assertEquals(first.id(), assertInstanceOf(ToolExecutionResultMessage.class, messages.get(3)).id());
        assertEquals(priorRetry, messages.get(4));
        assertEquals("third", assertInstanceOf(ToolExecutionResultMessage.class, messages.get(6)).id());
    }

    @Test
    void doesNotCompensateDuringAnActiveToolLoopOrChangeACompletedRound() {
        var memory = memory(100);
        memory.add(UserMessage.from("Start"));
        memory.add(AiMessage.from(first, second));
        memory.add(ToolExecutionResultMessage.from(second, "Second"));
        assertEquals(3, memory.messages().size());
        memory.add(ToolExecutionResultMessage.from(first, "First"));
        memory.add(AiMessage.from("Finished"));
        var completed = List.copyOf(memory.messages());

        ToolCallRecovery.recover(memory);
        memory.add(UserMessage.from("Next"));

        assertEquals(completed, memory.messages().subList(0, completed.size()));
    }

    @Test
    void recoveryRespectsTheWindowAndKeepsTheSystemMessage() {
        var system = SystemMessage.from("Instructions");
        store.updateMessages(memoryId, List.of(system, UserMessage.from("Old"), AiMessage.from(first, second)));
        var memory = memory(3);
        var next = UserMessage.from("Next");

        ToolCallRecovery.recover(memory);
        memory.add(next);

        // Evicting the call must evict its recovery results as well.
        assertEquals(List.of(system, next), memory.messages());
        assertEquals(memory.messages(), store.getMessages(memoryId));
    }

    private ChatMemory memory(int maxMessages) {
        return MessageWindowChatMemory.builder().id(memoryId)
                .maxMessages(maxMessages).alwaysKeepSystemMessageFirst(true).chatMemoryStore(store).build();
    }

    private static ToolExecutionRequest call(String id) {
        return ToolExecutionRequest.builder().id(id).name("edit").arguments("{}").build();
    }
}
