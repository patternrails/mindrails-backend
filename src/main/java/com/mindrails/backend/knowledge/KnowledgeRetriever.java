package com.mindrails.backend.knowledge;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.knowledge.service.KnowledgeBindingService;
import com.mindrails.backend.knowledge.service.KnowledgeResults;
import com.mindrails.backend.knowledge.service.KnowledgeSearchService;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import dev.langchain4j.rag.query.Query;
import java.util.List;
import java.util.UUID;

/**
 * Retrieves knowledge library content for ConversationRag without retaining session state.
 * Storage, indexing and binding rules stay inside this module.
 */
public final class KnowledgeRetriever implements ContentRetriever {
    public static final String BINDING = "mindrails.knowledge.binding";
    private final KnowledgeSearchService search;
    private final KnowledgeBindingService bindings;

    public KnowledgeRetriever(KnowledgeSearchService search, KnowledgeBindingService bindings) {
        this.search = search;
        this.bindings = bindings;
    }

    @Override
    public List<Content> retrieve(Query query) {
        if (query.metadata() == null || query.metadata().invocationParameters() == null) return List.of();
        var parameters = query.metadata().invocationParameters();
        UUID sessionId = parameters.get(LlmClient.SESSION_ID);
        if (sessionId == null && parameters.get(LlmClient.TASK_ID) == null) return List.of();
        LlmClient.checkCancelled(query.metadata().invocationContext());
        var binding = sessionId == null ? bindings.agent(parameters.get(LlmClient.AGENT_ID)) : bindings.session(sessionId);
        // Only immutable IDs and a version travel with the invocation, never a Session or service.
        parameters.put(BINDING, binding);
        var hits = new java.util.ArrayList<com.mindrails.backend.knowledge.model.KnowledgeSearchHit>();
        for (var id : binding.knowledgeBaseIds()) {
            LlmClient.checkCancelled(query.metadata().invocationContext());
            hits.addAll(KnowledgeResults.require(search.search(id, query.text())));
        }
        hits.sort(java.util.Comparator.comparingDouble(com.mindrails.backend.knowledge.model.KnowledgeSearchHit::score).reversed());
        if (sessionId == null) bindings.check(binding); else bindings.checkSession(sessionId, binding);
        return hits.stream().map(hit -> Content.from(TextSegment.from(hit.text(),
                Metadata.from("kind", "KNOWLEDGE")
                        .put("instructions", "Cite only supplied [K1], [K2] source identifiers.")
                        .put("id", hit.chunkId().toString()).put("title", hit.title()).put("location", hit.location())
                        .put("url", hit.url()).put("knowledgeBaseId", hit.knowledgeBaseId().toString())
                        .put("documentId", hit.documentId().toString()).put("revisionId", hit.revisionId().toString())
                        .put("indexGeneration", hit.indexGeneration().toString()).put("score", hit.score())))).toList();
    }
}
