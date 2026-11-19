package com.mindrails.backend.agent.service.internal.llm;

import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.service.tool.ToolExecution;
import java.util.List;

/** Invocation-local business recording; independent of the optional history/metrics listeners. */
public interface TaskExecutionObserver {
    void modelRequested(List<ChatMessage> messages);
    void modelResponded(ChatResponse response);
    void toolStarted(ToolExecutionRequest request);
    void toolCompleted(ToolExecution execution);
}
