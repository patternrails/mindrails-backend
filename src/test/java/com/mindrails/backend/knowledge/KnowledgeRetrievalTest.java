package com.mindrails.backend.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.model.KnowledgeBinding;
import com.mindrails.backend.knowledge.model.KnowledgeSearchHit;
import com.mindrails.backend.knowledge.service.KnowledgeBindingService;
import com.mindrails.backend.knowledge.service.KnowledgeSearchService;
import com.mindrails.backend.knowledge.service.KnowledgeException;
import com.mindrails.backend.knowledge.tool.KnowledgeTools;
import com.mindrails.backend.metrics.TestMetrics;
import com.mindrails.backend.agent.service.internal.llm.ConversationRag;
import com.mindrails.backend.history.TestHistory;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.rag.AugmentationRequest;
import dev.langchain4j.rag.query.Query;
import dev.langchain4j.rag.query.Metadata;
import dev.langchain4j.service.tool.ToolProviderRequest;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class KnowledgeRetrievalTest {
    private final KnowledgeBindingService bindings = mock(KnowledgeBindingService.class);
    private final KnowledgeSearchService search = mock(KnowledgeSearchService.class);
    private final KnowledgeProperties properties = new KnowledgeProperties();
    private final KnowledgeRetriever knowledge = new KnowledgeRetriever(search, bindings);
    private final Session session = Session.builder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID()).memoryId(UUID.randomUUID()).build();

    private Metadata metadata() {
        return Metadata.builder().chatMessage(UserMessage.from("original question"))
                .invocationContext(InvocationContext.builder().invocationId(UUID.randomUUID())
                        .invocationParameters(LlmClient.parameters(session, null, "REST")).build()).build();
    }

    @Test void unboundSessionsDoNotSearch() {
        var metadata = metadata();

        when(bindings.session(session.getId())).thenReturn(new KnowledgeBinding(session.getParentAgentId(), List.of(), 1));
        assertTrue(knowledge.retrieve(Query.from("question", metadata)).isEmpty());
        verifyNoInteractions(search);
    }

    @Test void sharedRetrieverResolvesEachSessionAndRagKeepsSourcesAndGuidance() {

        var kb = UUID.randomUUID();
        var binding = new KnowledgeBinding(session.getParentAgentId(), List.of(kb), 1);
        when(bindings.session(session.getId())).thenReturn(binding);
        var hit = new KnowledgeSearchHit(kb, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "Guide", "Relevant knowledge", "page 1", .9, "/api/knowledge/document/download");
        when(search.search(kb, "original question")).thenReturn(ServiceResult.success(List.of(hit)));
        var metadata = metadata();
        var rag = new ConversationRag(List.of(knowledge), 16000, TestHistory.disabled(), null);
        var result = rag.augment(new AugmentationRequest(UserMessage.from("original question"), metadata));
        assertTrue(result.chatMessage().toString().contains("[K1] Guide"));
        assertTrue(result.chatMessage().toString().contains("Cite only supplied [K1], [K2] source identifiers."));
        assertEquals(hit.chunkId().toString(), result.contents().getFirst().textSegment().metadata().getString("id"));
        assertEquals(hit.url(), result.contents().getFirst().textSegment().metadata().getString("url"));
        assertEquals(binding, metadata.invocationParameters().get(KnowledgeRetriever.BINDING));
        verify(bindings).checkSession(session.getId(), binding);
    }

    @Test void bindingChangesDuringRetrievalFailBeforeAnyKnowledgeReachesTheModel() {

        var binding = new KnowledgeBinding(session.getParentAgentId(), List.of(UUID.randomUUID()), 1);
        when(bindings.session(session.getId())).thenReturn(binding);
        when(search.search(binding.knowledgeBaseIds().getFirst(), "question")).thenReturn(ServiceResult.success(List.of()));
        doThrow(KnowledgeException.conflict("binding changed")).when(bindings).checkSession(session.getId(), binding);
        assertThrows(KnowledgeException.class, () -> knowledge.retrieve(Query.from("question", metadata())));
    }

    @Test void searchesOnlyCurrentAgentBasesAndNextTurnSeesRevocation() {

        var first = UUID.randomUUID();
        var second = UUID.randomUUID();
        var before = new KnowledgeBinding(session.getParentAgentId(), List.of(first, second), 1);
        var after = new KnowledgeBinding(session.getParentAgentId(), List.of(second), 2);
        when(bindings.session(session.getId())).thenReturn(before, after);
        when(search.search(any(), eq("question"))).thenReturn(ServiceResult.success(List.of()));
        knowledge.retrieve(Query.from("question", metadata()));
        knowledge.retrieve(Query.from("question", metadata()));
        verify(search, times(1)).search(first, "question");
        verify(search, times(2)).search(second, "question");
        verify(bindings).checkSession(session.getId(), before);
        verify(bindings).checkSession(session.getId(), after);
        var tools = new KnowledgeTools(null, bindings, properties, TestMetrics.disabled());
        var names = tools.provideTools(ToolProviderRequest.builder().userMessage(UserMessage.from("question"))
                .invocationContext(metadata().invocationContext()).build()).aiServiceTools().stream()
                .map(tool -> tool.toolSpecification().name()).collect(java.util.stream.Collectors.toSet());
        assertEquals(java.util.Set.of("knowledge_document_create", "knowledge_document_status"), names);
    }

    @Test void malformedKnowledgeArgumentsUseTheGlobalPolicyWithoutAgentMemory() {

        when(bindings.session(session.getId())).thenReturn(new KnowledgeBinding(session.getParentAgentId(), List.of(UUID.randomUUID()), 1));
        var context = metadata().invocationContext();
        var tools = new KnowledgeTools(null, bindings, properties, com.mindrails.backend.metrics.TestMetrics.disabled());
        var executor = tools.provideTools(ToolProviderRequest.builder().userMessage(UserMessage.from("question"))
                .invocationContext(context).build()).aiServiceTools().getFirst().toolExecutor();
        var call = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                .id("invalid-knowledge-call").name("knowledge_document_create").arguments("{").build();
        var errors = new com.mindrails.backend.tool.ToolErrorHandler();

        var result = dev.langchain4j.service.tool.ToolService.executeWithErrorHandling(call, executor, context,
                errors.argumentsErrorHandler(), errors.executionErrorHandler());

        assertEquals(true, result.isError());
        assertEquals("INVALID_ARGUMENT", new tools.jackson.databind.json.JsonMapper()
                .readTree(result.resultText()).get("code").asText());
        assertFalse(result.resultText().contains("memory"));
    }

    @Test void malformedToolCallsRetainTheirCallIdAndFailureMetrics() {

        when(bindings.session(session.getId())).thenReturn(new KnowledgeBinding(session.getParentAgentId(), List.of(UUID.randomUUID()), 1));
        var settings = new com.mindrails.backend.metrics.MetricsProperties();

        var repository = new com.mindrails.backend.metrics.InMemoryMetricsRepository(settings, new com.mindrails.backend.metrics.MetricsHealth());
        try (var metrics = new TestMetrics(settings, repository, new com.mindrails.backend.metrics.MetricsHealth(), false)) {
            var context = metadata().invocationContext();
            metrics.recorder().record(com.mindrails.backend.metrics.MetricsRecords.start(context.invocationParameters()));
            var tools = new KnowledgeTools(null, bindings, properties, metrics.recorder());
            var executor = tools.provideTools(ToolProviderRequest.builder().userMessage(UserMessage.from("question"))
                    .invocationContext(context).build()).aiServiceTools().getFirst().toolExecutor();
            var call = dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .id("original-call").name("knowledge_document_create").arguments("{").build();
            assertThrows(RuntimeException.class, () -> executor.executeWithContext(call, context));
            metrics.flush();
            var recorded = repository.snapshots().getFirst();
            assertEquals(1, recorded.functionCallCount());
            assertEquals(1, recorded.capabilityFailedCount());
            assertEquals("original-call", recorded.capabilityCalls().getFirst().providerToolCallId());
        }
    }
}
