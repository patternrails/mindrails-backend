package com.mindrails.backend.asset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mindrails.backend.asset.model.AssetVersion;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.knowledge.model.*;
import com.mindrails.backend.knowledge.service.*;
import org.junit.jupiter.api.Test;
import java.util.*;

class AssetKnowledgeFailureTest {
    @Test void automaticBaseUsesTheSystemDefaultInsteadOfAModuleProvider() {
        var bases = mock(KnowledgeBaseService.class);
        var registry = mock(AssetRegistry.class);
        var base = KnowledgeBase.builder().id(UUID.randomUUID()).build();
        when(bases.create(anyString(), anyString(), isNull())).thenReturn(ServiceResult.success(base));
        var service = new AssetKnowledgeService(mock(AssetStorageService.class), registry, null, bases);
        assertThat(service.automaticBase()).isEqualTo(base.getId());
        verify(bases).create(eq("Asset Storage"), anyString(), isNull());
    }

    @Test void parsingRetryWorksWithoutKnowledgeAndDoesNotSubmitAnIndex() {
        var assets = mock(AssetStorageService.class);
        var registry = mock(AssetRegistry.class);
        UUID id = UUID.randomUUID();
        when(assets.version(id)).thenReturn(AssetVersion.builder().id(id).state("FAILED").build());
        var service = new AssetKnowledgeService(assets, registry, null, null);
        service.retry(id);
        verify(assets).retry(id);
        verifyNoInteractions(registry);
    }

    @Test void indexingRetryDoesNotRestartReadyParsing() {
        var assets = mock(AssetStorageService.class);
        var registry = mock(AssetRegistry.class);
        var documents = mock(KnowledgeDocumentService.class);
        UUID id = UUID.randomUUID(), kb = UUID.randomUUID(), doc = UUID.randomUUID();
        when(assets.version(id)).thenReturn(AssetVersion.builder().id(id).state("READY").build());
        when(registry.links(id)).thenReturn(List.of(new AssetRegistry.Link(UUID.randomUUID(), id, kb, doc)));
        when(documents.get(kb, doc)).thenReturn(ServiceResult.success(
                KnowledgeDocument.builder().id(doc).state(KnowledgeDocumentState.FAILED).version(7).build()));
        when(documents.retry(kb, doc, 7)).thenReturn(ServiceResult.success(null));
        var service = new AssetKnowledgeService(assets, registry, documents, null);
        service.retry(id);
        verify(documents).retry(kb, doc, 7);
        verify(assets, never()).retry(any());
    }

    @Test void failedIndexReasonIsReturnedAlongsideItsState() {
        var assets = mock(AssetStorageService.class);
        var registry = mock(AssetRegistry.class);
        var documents = mock(KnowledgeDocumentService.class);
        var service = new AssetKnowledgeService(assets, registry,
                documents, null);
        UUID version = UUID.randomUUID(), kb = UUID.randomUUID(), doc = UUID.randomUUID(), revision = UUID.randomUUID();
        when(registry.links(version)).thenReturn(List.of(new AssetRegistry.Link(revision, version, kb, doc)));
        when(assets.version(version)).thenReturn(AssetVersion.builder().blobKey("original").build());
        when(documents.detail(kb, doc)).thenReturn(ServiceResult.success(new KnowledgeDocumentDetails(null,
                List.of(KnowledgeDocumentVersion.builder().id(revision).storageKey("original")
                        .state(DocumentVersionState.FAILED).error("HTTP 429 embedding rate limit").build()))));
        assertThat(service.status(version)).isEqualTo(new AssetKnowledgeService.Status("FAILED", "HTTP 429 embedding rate limit"));
    }
}
