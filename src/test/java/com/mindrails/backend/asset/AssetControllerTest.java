package com.mindrails.backend.asset;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.mindrails.backend.asset.api.web.*;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.*;
import com.mindrails.backend.asset.registry.AssetRegistry;
import com.mindrails.backend.asset.service.*;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

class AssetControllerTest {
    @Test
    void attachmentsCannotUseTheRemovedSessionRestChatRoute() throws Exception {
        var agents = mock(com.mindrails.backend.agent.service.AgentService.class);
        var mvc = MockMvcBuilders.standaloneSetup(
                new com.mindrails.backend.agent.api.web.AgentSessionController(agents))
                .setControllerAdvice(new AssetExceptionAdvice()).build();
        mvc.perform(post("/api/agent/" + UUID.randomUUID() + "/sessions/" + UUID.randomUUID() + "/chat")
                .contentType("application/json")
                .content("{\"assetVersionIds\":[\"" + UUID.randomUUID() + "\"]}"))
                .andExpect(status().isNotFound());
        verifyNoInteractions(agents);
    }

    @Test
    void configurationReportsAssetStorageAndLocalDeliverableAvailability() throws Exception {
        var properties = new AssetStorageProperties();
        var controller = new AssetStorageController(properties, null, null, null, null);
        var mvc = MockMvcBuilders.standaloneSetup(controller).build();
        mvc.perform(get("/api/asset-storage/config")).andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(true))
                .andExpect(jsonPath("$.deliverablesEnabled").value(true));
    }

    @Test
    void uploadKeepsHistoricalVersionErrorDetailsAndResponseFields() throws Exception {
        var assets = mock(AssetStorageService.class);
        var queries = mock(AssetQueryService.class);
        var properties = new AssetStorageProperties();
        var version = version();
        when(assets.upload(any(), any(), any(), any(), anyBoolean())).thenReturn(version);
        when(queries.version(version.getId())).thenReturn(new AssetDetails(
                new Asset(version.getAssetId(), AssetStorageService.LOCAL_OWNER, "Deck", "FILE", Instant.now()),
                version, false, null, "FAILED", "Embedding HTTP 401", List.of(version)));
        var mvc = MockMvcBuilders.standaloneSetup(new AssetStorageController(
                properties, assets, queries, null, null)).build();
        mvc.perform(multipart("/api/asset-storage")
                .file(new MockMultipartFile("file", "copy.html", "text/html", "<p>fixture</p>".getBytes())))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.version.id").value(version.getId().toString()))
                .andExpect(jsonPath("$.version.number").value(1))
                .andExpect(jsonPath("$.version.md5").value("md5-fixture"))
                .andExpect(jsonPath("$.version.blobKey").doesNotExist())
                .andExpect(jsonPath("$.knowledgeState").value("FAILED"))
                .andExpect(jsonPath("$.knowledgeError").value("Embedding HTTP 401"));
        verify(queries).version(version.getId());
    }

    @Test
    void fixedSourceValidationPaginationAndDownloadPreserveTheirContracts() throws Exception {
        var assets = mock(AssetStorageService.class);
        var registry = mock(AssetRegistry.class);
        var knowledge = mock(AssetKnowledgeService.class);
        var version = version();
        var block = new AssetBlock(UUID.randomUUID(), 0, "Budget 1200", "HTML / Budget / Paragraph 1");
        when(assets.version(version.getId())).thenReturn(version);
        when(assets.blocks(version.getId())).thenReturn(List.of(block));
        when(assets.open(version.getId())).thenReturn(new ByteArrayInputStream("source fixture".getBytes()));
        var properties = new AssetStorageProperties();
        var queries = new DefaultAssetQueryService(assets, registry, knowledge);
        var mvc = MockMvcBuilders.standaloneSetup(new AssetStorageController(
                properties, assets, queries, knowledge, null))
                .setControllerAdvice(new AssetExceptionAdvice(), new AssetErrorAdvice()).build();
        String prefix = "/api/asset-storage/versions/" + version.getId();
        mvc.perform(get(prefix + "/extractions/" + UUID.randomUUID() + "/blocks/" + block.id()))
                .andExpect(status().isNotFound());
        verify(assets, never()).blocks(any());
        mvc.perform(get(prefix + "/blocks?page=-1"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.message").value("Invalid page"));
        mvc.perform(get(prefix + "/extractions/" + version.getExtractionId() + "/blocks/" + block.id()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.block.text").value("Budget 1200"))
                .andExpect(jsonPath("$.downloadUrl").value(prefix + "/download"));
        mvc.perform(get(prefix + "/download"))
                .andExpect(status().isOk()).andExpect(content().bytes("source fixture".getBytes()))
                .andExpect(header().string("Content-Type", "application/octet-stream"));
    }

    private AssetVersion version() {
        return AssetVersion.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID())
                .extractionId(UUID.randomUUID()).number(1).filename("deck.html")
                .bytes("source fixture".length()).state("READY").md5("md5-fixture")
                .createdAt(Instant.now()).build();
    }
}
