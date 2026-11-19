package com.mindrails.backend.asset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.mindrails.backend.asset.api.web.AssetStorageController;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.*;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.service.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import java.time.Instant;
import java.util.*;

class AssetVersionViewTest {
    @Test void duplicateUploadAndPollingReturnMatchedHistoricalVersionAndIndexFailure() throws Exception {
        var assets = mock(AssetStorageService.class);
        var knowledge = mock(AssetKnowledgeService.class);
        var registry = mock(AssetRegistry.class);
        var properties = new AssetStorageProperties();

        var queries = new DefaultAssetQueryService(assets, registry, knowledge);
        var controller = new AssetStorageController(properties, assets, queries, knowledge, null);
        UUID assetId = UUID.randomUUID();
        var old = AssetVersion.builder().id(UUID.randomUUID()).assetId(assetId).number(1).build();
        var current = AssetVersion.builder().id(UUID.randomUUID()).assetId(assetId).number(2).build();
        when(assets.asset(assetId)).thenReturn(new Asset(assetId, AssetStorageService.LOCAL_OWNER,
                "Deck", "FILE", Instant.now()));
        when(assets.versions(assetId)).thenReturn(List.of(current, old));
        when(assets.version(old.getId())).thenReturn(old);
        when(knowledge.status(old.getId())).thenReturn(new AssetKnowledgeService.Status(
                "FAILED", "Embedding request failed: HTTP 401 (invalid provider credentials)"));
        when(assets.upload(any(), any(), any(), any(), anyBoolean())).thenReturn(old);
        var response = controller.upload(new MockMultipartFile("file", "copy.html", "text/html", new byte[]{1}), null, "FILE");
        assertThat(response.version().id()).isEqualTo(old.getId());
        assertThat(controller.getVersion(old.getId()).version().id()).isEqualTo(old.getId());
        assertThat(response.knowledgeError()).contains("HTTP 401");
    }
}
