package com.mindrails.backend.knowledge;

import dev.langchain4j.rag.content.retriever.ContentRetriever;
import com.mindrails.backend.knowledge.tool.KnowledgeTools;

/** Test-only fixture for knowledge retrieval and tools. */
public record TestKnowledge(ContentRetriever retriever, KnowledgeTools tools, int budget) {}
