package com.mindrails.backend.asset;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.agent.registry.ReadOnlySessionRegistry;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.*;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.asset.store.LocalAssetBlobStore;
import dev.langchain4j.data.document.Metadata;
import dev.langchain4j.data.segment.TextSegment;
import dev.langchain4j.rag.content.Content;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import tools.jackson.databind.json.JsonMapper;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

class AssetMissingExtractionTest {
    @TempDir Path directory;

    @Test void readyMetadataWithMissingOrCorruptExtractionIsAnActionableStorageFailure() throws Exception {
        var registry = mock(AssetRegistry.class);
        var version = AssetVersion.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .extractionId(UUID.randomUUID()).state("READY").filename("contract.docx").build();
        when(registry.version(version.getId())).thenReturn(version);
        when(registry.asset(version.getAssetId())).thenReturn(new Asset(version.getAssetId(),
                AssetStorageService.LOCAL_OWNER, "Contract", "FILE", Instant.now()));
        var properties = new AssetStorageProperties(new FileStorageProperties(directory));
        try (var blobs = new LocalAssetBlobStore(properties.directory())) {
            var service = new DefaultAssetStorageService(registry, blobs, properties, new JsonMapper());
            var error = assertThrows(AssetException.class, () -> service.blocks(version.getId()));
            assertEquals(AssetException.Reason.UNAVAILABLE, error.reason());
            assertTrue(error.getMessage().contains("upload the original again"));
            assertFalse(error.getMessage().contains(directory.toString()));
            assertNotNull(error.getCause());
            blobs.put(DefaultAssetStorageService.blocksKey(version), new java.io.ByteArrayInputStream("not-json".getBytes()));
            assertEquals(AssetException.Reason.UNAVAILABLE, assertThrows(AssetException.class, () -> service.blocks(version.getId())).reason());
        }
        verify(registry, never()).retry(any());
    }

    @ParameterizedTest
    @EnumSource(value=AssetException.Reason.class, names={"UNAVAILABLE", "NOT_FOUND", "CONFLICT"})
    void unavailableLinkedAssetRetainsAuthorizedKnowledgeTextAndCitation(AssetException.Reason reason) {
        var assets = mock(AssetStorageService.class);
        var registry = mock(AssetRegistry.class);
        UUID revision = UUID.randomUUID(), version = UUID.randomUUID();
        var source = Content.from(TextSegment.from("Verified knowledge passage", Metadata.from("kind", "KNOWLEDGE")
                .put("revisionId", revision.toString()).put("location", "Paragraph 1")
                .put("url", "/api/knowledge-bases/source/original")));
        when(registry.linkedVersion(revision)).thenReturn(Optional.of(version));
        when(assets.version(version)).thenReturn(AssetVersion.builder().id(version).build());
        when(assets.blocks(version)).thenThrow(new AssetException(reason, "Unavailable asset"));
        var retriever = new AssetConversationRetriever(assets, registry, mock(AssetKnowledgeService.class), null,
                mock(ReadOnlySessionRegistry.class), new AssetStorageProperties());
        assertSame(source, retriever.resolveKnowledge(source));
        assertNull(source.textSegment().metadata().getString("assetVersionId"));
    }

    @Test void unavailableEnrichmentDoesNotHideUnrelatedProgrammingOrValidationErrors() {
        var assets = mock(AssetStorageService.class); var registry = mock(AssetRegistry.class);
        UUID revision = UUID.randomUUID(), version = UUID.randomUUID();
        var source = Content.from(TextSegment.from("Knowledge", Metadata.from("kind", "KNOWLEDGE").put("revisionId", revision.toString())));
        when(registry.linkedVersion(revision)).thenReturn(Optional.of(version));
        when(assets.version(version)).thenThrow(AssetException.invalid("invalid metadata"));
        var retriever = new AssetConversationRetriever(assets, registry, mock(AssetKnowledgeService.class), null,
                mock(ReadOnlySessionRegistry.class), new AssetStorageProperties());
        assertThrows(AssetException.class, () -> retriever.resolveKnowledge(source));
        doThrow(new IllegalStateException("programming error")).when(assets).version(version);
        assertThrows(IllegalStateException.class, () -> retriever.resolveKnowledge(source));
    }
}
