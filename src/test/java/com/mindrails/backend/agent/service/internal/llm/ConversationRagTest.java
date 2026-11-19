package com.mindrails.backend.agent.service.internal.llm;

import static org.junit.jupiter.api.Assertions.*;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.history.TestHistory;
import com.mindrails.backend.history.model.HistoryRecord;
import com.mindrails.backend.memory.agent.AgentMemoryRetrieval;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.content.Content;
import dev.langchain4j.rag.content.retriever.ContentRetriever;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class ConversationRagTest {
    @Test void explicitAssetCoordinatorSuppliesInventoryResolvesSourcesAndRecordsSelectedCitations() {
        var assets = org.mockito.Mockito.mock(com.mindrails.backend.asset.service.AssetConversationRetriever.class);
        var original = document("Knowledge excerpt");
        String sourceId = UUID.randomUUID().toString();
        String url = "/api/asset-storage/versions/" + UUID.randomUUID() + "/download";
        var resolved = Content.from(TextSegment.from("Budget 1200", Metadata.from("kind", "ASSET")
                .put("id", sourceId).put("title", "Contract").put("location", "Page 1")
                .put("coverage", "full").put("url", url)));
        org.mockito.Mockito.when(assets.manifest(org.mockito.ArgumentMatchers.any()))
                .thenReturn("\nAttached inventory marker\n");
        org.mockito.Mockito.when(assets.resolveKnowledge(original)).thenReturn(resolved);
        var archive = new ArrayList<HistoryRecord>();
        var rag = new ConversationRag(List.of(query -> List.of(original)), 2000,
                TestHistory.create(archive::add, null), assets);
        var result = rag.augment(request());
        assertTrue(result.chatMessage().toString().contains("Attached inventory marker"));
        assertTrue(result.chatMessage().toString().contains("[F1] Contract"));
        assertEquals(1, result.contents().size());
        var selected = result.contents().getFirst();
        assertEquals(url, selected.textSegment().metadata().getString("url"));
        org.mockito.Mockito.verify(assets).record(org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.argThat(content ->
                        sourceId.equals(content.textSegment().metadata().getString("id"))
                                && "F1 · Contract · Page 1".equals(content.textSegment().metadata().getString("key"))));
        assertTrue(archive.stream().anyMatch(record -> record.metadata().contains(sourceId)
                && record.metadata().contains(url)));
    }

    private AugmentationRequest request() {
        var session = Session.builder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID()).memoryId(UUID.randomUUID()).build();
        var message = UserMessage.from("original question");
        var context = InvocationContext.builder().invocationId(UUID.randomUUID())
                .invocationParameters(LlmClient.parameters(session, null, "REST")).build();
        return new AugmentationRequest(message, dev.langchain4j.rag.query.Metadata.builder()
                .chatMessage(message).invocationContext(context).build());
    }

    private Content document(String text) {
        return Content.from(TextSegment.from(text, Metadata.from("kind", "KNOWLEDGE")
                .put("id", UUID.randomUUID().toString()).put("title", "Guide").put("location", "page 1")
                .put("knowledgeBaseId", UUID.randomUUID().toString()).put("documentId", UUID.randomUUID().toString())
                .put("revisionId", UUID.randomUUID().toString()).put("indexGeneration", UUID.randomUUID().toString()).put("score", .9)));
    }

    @Test void sourcesQueryTheOriginalInputAndOnlySelectedKnowledgeIsArchived() {
        var queries = new ArrayList<String>();
        var fact = Content.from(TextSegment.from("Shared fact", Metadata.from("kind", "AGENT_MEMORY").put("id", UUID.randomUUID().toString()).put("key", "fact")));
        var selected = document("Relevant guide");
        ContentRetriever memory = query -> { queries.add(query.text()); return List.of(fact); };
        ContentRetriever knowledge = query -> { queries.add(query.text()); return List.of(document("x".repeat(1000)), selected); };
        var archive = new ArrayList<HistoryRecord>();
        var history = TestHistory.create(archive::add, null);
        var result = new ConversationRag(List.of(memory, knowledge), 350, history, null).augment(request());
        assertEquals(List.of("original question", "original question"), queries);
        assertEquals(2, result.contents().size());
        assertTrue(result.chatMessage().toString().contains(AgentMemoryRetrieval.PREFIX));
        assertTrue(result.chatMessage().toString().contains("[K1] Guide"));
        assertFalse(result.chatMessage().toString().contains("[K2]"));
        assertEquals(1, archive.size());
        assertTrue(archive.getFirst().metadata().contains(selected.textSegment().metadata().getString("id")));
        var next = new ConversationRag(List.of(query -> List.of()), 350, TestHistory.disabled(), null).augment(request());
        assertEquals(UserMessage.from("original question"), next.chatMessage());
        assertTrue(next.contents().isEmpty());
    }
}
