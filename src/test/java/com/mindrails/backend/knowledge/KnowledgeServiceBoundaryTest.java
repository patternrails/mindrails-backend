package com.mindrails.backend.knowledge;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.config.KnowledgeProperties;
import com.mindrails.backend.knowledge.index.KnowledgeIndexFactory;
import com.mindrails.backend.knowledge.model.KnowledgeBase;
import com.mindrails.backend.knowledge.model.KnowledgeBaseState;
import com.mindrails.backend.knowledge.model.KnowledgeDocument;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentState;
import com.mindrails.backend.knowledge.model.KnowledgeDocumentUpload;
import com.mindrails.backend.knowledge.model.KnowledgeIngestionJob;
import com.mindrails.backend.knowledge.model.KnowledgeSourceType;
import com.mindrails.backend.knowledge.parser.DocumentParserRegistry;
import com.mindrails.backend.knowledge.registry.KnowledgeRegistry;
import com.mindrails.backend.knowledge.service.DefaultKnowledgeDocumentService;
import com.mindrails.backend.knowledge.service.DefaultKnowledgeSearchService;
import com.mindrails.backend.knowledge.service.KnowledgeBindingService;
import com.mindrails.backend.knowledge.service.KnowledgeException;
import com.mindrails.backend.knowledge.service.KnowledgeResults;
import com.mindrails.backend.knowledge.service.internal.KnowledgeAccess;
import com.mindrails.backend.knowledge.service.internal.KnowledgeTaskService;
import com.mindrails.backend.knowledge.store.DocumentStorage;

import org.junit.jupiter.api.Test;

import tools.jackson.databind.json.JsonMapper;

import java.io.ByteArrayInputStream;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

class KnowledgeServiceBoundaryTest {
    private final KnowledgeRegistry registry = mock(KnowledgeRegistry.class);
    private final KnowledgeBindingService bindings = mock(KnowledgeBindingService.class);
    private final KnowledgeProperties properties = new KnowledgeProperties();
    private final KnowledgeAccess access = new KnowledgeAccess(registry, bindings);
    private final DocumentStorage storage = mock(DocumentStorage.class);
    private final DocumentParserRegistry parsers = mock(DocumentParserRegistry.class);
    private final KnowledgeTaskService tasks = mock(KnowledgeTaskService.class);
    private final DefaultKnowledgeDocumentService documents =
            new DefaultKnowledgeDocumentService(
                    access,
                    properties,
                    storage,
                    parsers,
                    bindings,
                    tasks,
                    JsonMapper.builder().build());

    @Test
    void aDocumentFromAnotherKnowledgeBaseIsNotExposed() {
        UUID requestedBase = UUID.randomUUID();
        UUID documentId = UUID.randomUUID();
        when(registry.getDocument(documentId))
                .thenReturn(
                        KnowledgeDocument.builder()
                                .id(documentId)
                                .knowledgeBaseId(UUID.randomUUID())
                                .build());
        assertEquals(
                ServiceResult.failure(
                        ServiceResult.ErrorCode.NOT_FOUND, "Knowledge resource not found"),
                documents.get(requestedBase, documentId));
        verifyNoInteractions(storage, parsers, tasks);
    }

    @Test
    void emptyOrDeletingKnowledgeBaseNeverCallsEmbeddingOrQdrant() {
        UUID id = UUID.randomUUID();
        var base = KnowledgeBase.builder().id(id).state(KnowledgeBaseState.ACTIVE).build();
        when(registry.getKnowledgeBase(id)).thenReturn(base);
        when(registry.listDocuments(id)).thenReturn(List.of());
        var indexes = mock(KnowledgeIndexFactory.class);
        var search = new DefaultKnowledgeSearchService(access, properties, indexes);
        assertEquals(ServiceResult.success(List.of()), search.search(id, "database"));
        base.setState(KnowledgeBaseState.DELETING);
        assertEquals(
                ServiceResult.failure(
                        ServiceResult.ErrorCode.NOT_FOUND, "Knowledge resource not found"),
                search.search(id, "database"));
        verifyNoInteractions(indexes);
    }

    @Test
    void uploadStoresAndDetectsContentBeforeEnteringMetadataTransaction() {
        UUID id = UUID.randomUUID();
        UUID jobId = UUID.randomUUID();
        var insideTransaction = new AtomicBoolean();
        when(registry.getKnowledgeBase(id))
                .thenReturn(
                        KnowledgeBase.builder().id(id).state(KnowledgeBaseState.ACTIVE).build());
        when(registry.listDocuments(id)).thenReturn(List.of());
        when(registry.transaction(any()))
                .thenAnswer(
                        call -> {
                            insideTransaction.set(true);
                            try {
                                return ((Supplier<?>) call.getArgument(0)).get();
                            } finally {
                                insideTransaction.set(false);
                            }
                        });
        when(storage.write(any(), eq("note.txt"), eq("txt"), any()))
                .thenAnswer(
                        call -> {
                            assertFalse(insideTransaction.get());
                            return new DocumentStorage.Stored(
                                    call.getArgument(0) + "/source.txt", "hash", 4);
                        });
        when(storage.path(anyString())).thenReturn(Path.of("synthetic-document.txt"));
        when(parsers.mediaType(any()))
                .thenAnswer(
                        call -> {
                            assertFalse(insideTransaction.get());
                            return "text/plain";
                        });
        when(tasks.enqueue(any(), any(), any()))
                .thenReturn(KnowledgeIngestionJob.builder().id(jobId).build());
        var result =
                documents.upload(
                        new KnowledgeDocumentUpload(
                                id,
                                "note",
                                "Note",
                                List.of(),
                                "note.txt",
                                new ByteArrayInputStream(
                                        "test".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        var submission = KnowledgeResults.require(result);
        assertEquals(jobId, submission.jobId());
        var document = org.mockito.ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(registry).insert(document.capture());
        assertEquals(KnowledgeSourceType.UPLOAD, document.getValue().getSourceType());
        assertEquals(KnowledgeDocumentState.QUEUED, document.getValue().getState());
    }

    @Test
    void storageFailureRetainsItsDiagnosticCause() {
        var original = new java.io.IOException("synthetic I/O failure");
        UUID id = UUID.randomUUID();
        when(registry.getDocument(id)).thenThrow(KnowledgeException.unavailable(original));
        var error =
                assertThrows(KnowledgeException.class, () -> documents.get(UUID.randomUUID(), id));
        assertSame(original, error.getCause());
    }
}
