package com.mindrails.backend.image;

import com.mindrails.backend.common.config.FileStorageProperties;
import com.mindrails.backend.tool.spec.ImageGenerationTools;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import com.mindrails.backend.agent.model.*;
import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.asset.config.AssetStorageProperties;
import com.mindrails.backend.asset.model.AssetVersion;
import com.mindrails.backend.asset.service.*;
import com.mindrails.backend.identity.*;
import com.mindrails.backend.metrics.TestMetrics;
import dev.langchain4j.invocation.*;
import dev.langchain4j.service.tool.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.util.*;

class ImageGenerationToolsTest {
    @TempDir Path dir;
    final SessionRegistry sessions = new SessionRegistryConfiguration().sessionRegistry();
    final UUID owner = UUID.randomUUID();
    final UUID request = UUID.randomUUID();
    final Session session = Session.builder().id(UUID.randomUUID()).parentAgentId(UUID.randomUUID()).ownerId(owner).memoryId(UUID.randomUUID()).build();
    final ImageProviderService providers = mock(ImageProviderService.class);
    final ImageGenerationClient client = mock(ImageGenerationClient.class);
    final AssetStorageService assets = mock(AssetStorageService.class);
    SessionDeliverables files;
    ImageGenerationTools tools;

    void setup() {
        sessions.insertIfAbsent(session);
        var properties = properties();
        files = new SessionDeliverables(properties, sessions);
        tools = new ImageGenerationTools(providers, client, assets, files, sessions, TestMetrics.disabled());
    }
    InvocationContext context() {
        return InvocationContext.builder().chatMemoryId(session.getMemoryId())
                .invocationParameters(InvocationParameters.from(Map.of(LlmClient.SESSION_ID, session.getId(), LlmClient.REQUEST_ID, request))).build();
    }
    @Test void generatesOwnerScopedAssetAndPersistentDeliverableWithImageMetadata() throws Exception {
        setup(); var config = ImageGenerationClientTest.config("openai", "https://example.test/images/generations");
        when(providers.select(null)).thenReturn(config); when(providers.get(config.info().id())).thenReturn(config.info());
        when(client.generate(eq(config), eq("draw a cat"), any(ImageGenerationOptions.class), any())).thenReturn(List.of(new ImageGenerationClient.Image(ImageGenerationClientTest.PNG, "png")));
        var version = AssetVersion.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID()).build();
        when(assets.upload(anyString(), anyString(), eq("FILE"), any(), eq(false))).thenAnswer(invocation -> {
            assertEquals(owner, ResourceAccess.creationOwner()); return version;
        });
        try (var scope = ResourceAccess.as(owner)) {
            var result = (Map<?, ?>) tools.generate("draw a cat", null, context());
            assertEquals(true, result.get("success")); assertTrue(result.get("url").toString().contains(version.getId().toString()));
            var persisted = new SessionDeliverables(properties(), sessions).list(session.getId());
            assertEquals(1, persisted.size());
            var file = persisted.getFirst(); assertEquals(version.getId(), file.versionId()); assertEquals("image/png", file.mediaType()); assertEquals(request, file.requestId());
            assertArrayEquals(ImageGenerationClientTest.PNG, Files.readAllBytes(files.download(session.getId(), file.id())));
            files.publishAll(session.getId()); assertEquals(version.getId(), files.list(session.getId()).getFirst().versionId());
            assertEquals(request, files.list(session.getId()).getFirst().requestId());
            files.delete(session.getId(), file.id()); assertTrue(files.list(session.getId()).isEmpty());
        }
        try (var scope = ResourceAccess.as(UUID.randomUUID())) {
            assertThrows(RuntimeException.class, () -> tools.generate("draw a cat", null, context()));
        }
    }
    AssetStorageProperties properties() {
        return new AssetStorageProperties(new FileStorageProperties(dir));
    }
    @Test void toolAvailabilityTracksTheCurrentCatalogAndIsNotGlobalOrAvailableToTasks() {
        setup(); var request = ToolProviderRequest.builder().userMessage(dev.langchain4j.data.message.UserMessage.from("draw a cat")).invocationContext(context()).build();
        when(providers.available()).thenReturn(List.of()); assertTrue(tools.provideTools(request).tools().isEmpty());
        assertTrue(tools.instructions().contains("unavailable"));
        when(providers.available()).thenReturn(List.of(ImageGenerationClientTest.config("qwen", "https://example.test").info()));
        when(providers.choices()).thenReturn("tested options");
        var supplied = tools.provideTools(request).aiServiceTools(); assertEquals(1, supplied.size());
        assertEquals("image_generate", supplied.getFirst().name());
        assertTrue(supplied.getFirst().toolSpecification().description().contains("ONLY"));
        assertEquals(List.of("prompt"), supplied.getFirst().toolSpecification().parameters().required());
        assertTrue(tools.buildTools(TestMetrics.disabled()).isEmpty());
        var task = InvocationContext.builder().invocationParameters(InvocationParameters.from(Map.of(LlmClient.TASK_ID, UUID.randomUUID()))).build();
        assertTrue(tools.provideTools(ToolProviderRequest.builder().userMessage(dev.langchain4j.data.message.UserMessage.from("draw a cat")).invocationContext(task).build()).tools().isEmpty());
        when(providers.available()).thenReturn(List.of()); assertTrue(tools.provideTools(request).tools().isEmpty());
    }
    @Test void unknownProviderReturnsClarificationWithoutCallingVendorAndMemoryCannotCrossSessions() {
        setup();
        when(providers.select("other-vendor")).thenThrow(ImageGenerationException.invalid("Ask which tested provider to use"));
        try(var scope = ResourceAccess.as(owner)) {
            var result = (Map<?, ?>) tools.generate("draw a cat", "other-vendor", context());
            assertEquals(false, result.get("success")); assertTrue(result.get("message").toString().contains("Ask"));
            verifyNoInteractions(client, assets);
            var mismatched = InvocationContext.builder().chatMemoryId(UUID.randomUUID()).invocationParameters(context().invocationParameters()).build();
            assertThrows(IllegalArgumentException.class, () -> tools.generate("draw a cat", null, mismatched));
        }
    }
    @Test void toolSchemaBindsDimensionsAndSnakeCaseOptionsAndPublishesEveryImageToTheSameRound() {
        setup(); var config = ImageGenerationClientTest.config("openai", "https://example.test");
        when(providers.available()).thenReturn(List.of(config.info())); when(providers.select(null)).thenReturn(config);
        when(providers.get(config.info().id())).thenReturn(config.info());
        when(client.generate(eq(config), eq("draw two"), any(ImageGenerationOptions.class), any())).thenAnswer(invocation -> {
            ImageGenerationOptions options = invocation.getArgument(2);
            assertEquals(1536, options.width()); assertEquals(864, options.height()); assertEquals(2, options.n());
            assertEquals("webp", options.outputFormat()); assertEquals(80, options.outputCompression());
            return List.of(new ImageGenerationClient.Image(ImageGenerationClientTest.PNG, "png"),
                    new ImageGenerationClient.Image(ImageGenerationClientTest.PNG, "png"));
        });
        when(assets.upload(anyString(), anyString(), eq("FILE"), any(), eq(false)))
                .thenAnswer(invocation -> AssetVersion.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID()).build());
        var tool = tools.provideTools(ToolProviderRequest.builder().userMessage(dev.langchain4j.data.message.UserMessage.from("draw two"))
                .invocationContext(context()).build()).aiServiceTools().getFirst();
        assertEquals(Set.of("prompt", "provider", "width", "height", "size", "n", "quality", "background", "output_format", "output_compression", "moderation", "negative_prompt", "seed", "prompt_extend", "prompt_extend_mode", "enable_thinking", "watermark"),
                tool.toolSpecification().parameters().properties().keySet());
        try (var scope = ResourceAccess.as(owner)) {
            var result = tool.toolExecutor().executeWithContext(dev.langchain4j.agent.tool.ToolExecutionRequest.builder()
                    .id("two-images").name("image_generate").arguments("{\"prompt\":\"draw two\",\"width\":1536,\"height\":864,\"n\":2,\"output_format\":\"webp\",\"output_compression\":80}").build(), context());
            var json = new tools.jackson.databind.json.JsonMapper().readTree(result.resultText());
            assertTrue(json.path("success").asBoolean(), result.resultText()); assertEquals(2, json.path("images").size());
            assertEquals(2, files.list(session.getId()).size());
            files.list(session.getId()).forEach(file -> assertEquals(request, file.requestId()));
        }
    }

    @Test void partialPublicationReportsTheAlreadySavedAssetsWithoutRetryingPaidGeneration() {
        setup(); var config = ImageGenerationClientTest.config("openai", "https://example.test");
        when(providers.select(null)).thenReturn(config); when(providers.get(config.info().id())).thenReturn(config.info());
        when(client.generate(eq(config), eq("draw two"), any(ImageGenerationOptions.class), any())).thenReturn(List.of(
                new ImageGenerationClient.Image(ImageGenerationClientTest.PNG, "png"), new ImageGenerationClient.Image(ImageGenerationClientTest.PNG, "png")));
        var version = AssetVersion.builder().id(UUID.randomUUID()).assetId(UUID.randomUUID()).build();
        when(assets.upload(anyString(), anyString(), eq("FILE"), any(), eq(false))).thenReturn(version)
                .thenThrow(new IllegalStateException("private storage diagnostic"));
        try (var scope = ResourceAccess.as(owner)) {
            var result = (Map<?, ?>) tools.generate("draw two", null, ImageGenerationOptions.builder().n(2).build(), context());
            assertEquals(false, result.get("success")); assertEquals("IMAGE_PUBLISH_PARTIAL", result.get("code"));
            assertEquals(1, ((List<?>) result.get("images")).size()); assertEquals(1, files.list(session.getId()).size());
            assertFalse(result.toString().contains("private storage diagnostic"));
            verify(client, times(1)).generate(any(), anyString(), any(ImageGenerationOptions.class), any());
        }
    }

}
