package com.mindrails.backend.memory;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.memory.ChatMemory;
import java.util.ArrayList;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;

/** Repairs interrupted tool rounds before an invocation copies memory into its model request. */
@Slf4j
public final class ToolCallRecovery {
    private static final String UNKNOWN_RESULT = """
            {"status":"UNKNOWN","code":"TOOL_RESULT_MISSING","message":"No result was recorded for this tool call before the previous turn ended. The operation may or may not have executed. Verify its effects before retrying; do not assume success."}
            """;

    private ToolCallRecovery() {}

    /** Call only at the start of a new invocation, never while its tools are executing. */
    public static void recover(ChatMemory memory) {
        var messages = memory.messages();
        var recovered = new ArrayList<ChatMessage>();
        for (int index = 0; index < messages.size(); index++) {
            var message = messages.get(index);
            recovered.add(message);
            if (!(message instanceof AiMessage ai) || !ai.hasToolExecutionRequests()) {
                continue;
            }
            var missing = new ArrayList<>(ai.toolExecutionRequests());
            while (index + 1 < messages.size()
                    && messages.get(index + 1) instanceof ToolExecutionResultMessage result) {
                recovered.add(result);
                index++;
                for (int call = 0; call < missing.size(); call++) {
                    if (matches(missing.get(call), result)) {
                        missing.remove(call);
                        break;
                    }
                }
            }
            for (var request : missing) {
                recovered.add(ToolExecutionResultMessage.from(request, UNKNOWN_RESULT));
                log.warn("Recovering missing tool result: memoryId={}, toolCallId={}, toolName={}",
                        memory.id(), request.id(), request.name());
            }
        }
        if (!messages.equals(recovered)) {
            memory.set(recovered);
        }
    }

    private static boolean matches(ToolExecutionRequest request, ToolExecutionResultMessage result) {
        return Objects.equals(request.id(), result.id())
                && (request.id() != null || Objects.equals(request.name(), result.toolName()));
    }
}
