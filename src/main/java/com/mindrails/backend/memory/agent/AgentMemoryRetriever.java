package com.mindrails.backend.memory.agent;

import com.mindrails.backend.agent.model.LlmClient;

import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;

import java.util.List;
import java.util.UUID;

/** Retrieves the current Agent facts for ConversationRag; no session binding or content is retained. */
public final class AgentMemoryRetriever implements ContentRetriever {
    private final AgentMemoryService memory;
    public AgentMemoryRetriever(AgentMemoryService memory) { this.memory = memory; }

    @Override
    public List<Content> retrieve(Query query) {
        if (!memory.available()) return List.of();
        if (query.metadata() == null || query.metadata().invocationParameters() == null) return List.of();
        var parameters = query.metadata().invocationParameters();
        UUID sessionId = parameters.get(LlmClient.SESSION_ID), agentId = parameters.get(LlmClient.AGENT_ID);
        UUID taskId = parameters.get(LlmClient.TASK_ID);
        if (sessionId == null && taskId == null || agentId == null) return List.of();
        LlmClient.checkCancelled(query.metadata().invocationContext());
        return (taskId == null ? memory.forSession(sessionId, agentId, query.text()) : memory.forTask(taskId, agentId, query.text())).stream()
                .map(entry -> Content.from(TextSegment.from(AgentMemoryRetrieval.format(entry),
                        Metadata.from("kind", "AGENT_MEMORY").put("id", entry.id().toString())
                                .put("key", entry.key()).put("version", entry.version())))).toList();
    }
}
