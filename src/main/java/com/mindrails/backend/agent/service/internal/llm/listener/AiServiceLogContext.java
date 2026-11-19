package com.mindrails.backend.agent.service.internal.llm.listener;

import com.mindrails.backend.agent.model.LlmClient;
import dev.langchain4j.invocation.InvocationContext;

/** Logs only correlation identifiers, never method arguments or invocation parameters. */
record AiServiceLogContext(InvocationContext context) {

    String modelName(String reportedModelName) {
        return reportedModelName != null ? reportedModelName
                : context.invocationParameters() == null ? null
                : context.invocationParameters().get(LlmClient.MODEL_NAME);
    }

    @Override
    public String toString() {
        var value = new StringBuilder("invocationId=").append(context.invocationId());
        if (context.invocationParameters() != null) {
            append(value, "requestId", context.invocationParameters().get(LlmClient.REQUEST_ID));
            append(value, "runId", context.invocationParameters().get(LlmClient.RUN_ID));
        }
        append(value, "chatMemoryId", context.chatMemoryId());
        return value.toString();
    }

    private static void append(StringBuilder value, String name, Object field) {
        if (field != null) value.append(", ").append(name).append('=').append(field);
    }
}
