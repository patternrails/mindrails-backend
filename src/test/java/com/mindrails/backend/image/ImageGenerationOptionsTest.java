package com.mindrails.backend.image;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import java.net.http.HttpClient;
import java.util.List;
import org.junit.jupiter.api.Test;

class ImageGenerationOptionsTest {
    @Test void publicProviderConfigCannotSerializeItsCredentialAcrossTheToolBoundary() {
        var config = ImageGenerationClientTest.config("openai", "https://example.test");
        String json = new tools.jackson.databind.json.JsonMapper().writeValueAsString(config);
        assertFalse(json.contains("private-api-key")); assertFalse(json.contains("apiKey"));
        assertFalse(config.toString().contains("private-api-key"));
    }

    @Test void dimensionsOverrideProviderDefaultsWithoutMutatingConfiguration() {
        var provider = ImageGenerationClientTest.config("openai", "https://example.test").info();
        var options = ImageGenerationOptions.builder().width(1536).height(864).n(2).build();
        var request = options.request(provider, "draw two pictures");
        assertEquals("1536x864", request.get("size")); assertEquals(2, request.get("n"));
        assertEquals("1024x1024", provider.size());
        assertEquals("1024x1024", ImageGenerationOptions.defaults().request(provider, "draw").get("size"));
        assertEquals("auto", ImageGenerationOptions.builder().size("auto").build().request(provider, "draw").get("size"));
    }
    @Test void qwenOverridesRetainExplicitFalseAndZeroValues() {
        var options = ImageGenerationOptions.builder().width(1024).height(1536).n(2)
                .negativePrompt("text").seed(0L).promptExtend(true).promptExtendMode("agent")
                .enableThinking(false).watermark(false).build();
        var request = options.request(ImageGenerationClientTest.config("qwen", "https://example.test").info(), "draw");
        assertEquals(0L, request.get("seed")); assertEquals(false, request.get("enable_thinking"));
        assertEquals(false, request.get("watermark")); assertEquals("agent", request.get("prompt_extend_mode"));
        assertEquals("text", request.get("negative_prompt")); assertFalse(request.containsKey("output_format"));
    }
    @Test void invalidCombinationsAreRejectedBeforeSendingAnyPaidRequest() {
        var http = mock(HttpClient.class); var client = new ImageGenerationClient(http);
        var openai = ImageGenerationClientTest.config("openai", "https://example.test");
        for (var options : List.of(
                ImageGenerationOptions.builder().width(1024).build(),
                ImageGenerationOptions.builder().width(-1024).height(1024).build(),
                ImageGenerationOptions.builder().width(1000).height(1024).build(),
                ImageGenerationOptions.builder().width(1024).height(1024).size("auto").build(),
                ImageGenerationOptions.builder().size("4096x4096").build(),
                ImageGenerationOptions.builder().size("1024x256").build(),
                ImageGenerationOptions.builder().n(0).build(), ImageGenerationOptions.builder().n(11).build(),
                ImageGenerationOptions.builder().quality("impossible").build(),
                ImageGenerationOptions.builder().background("transparent").outputFormat("jpeg").build(),
                ImageGenerationOptions.builder().outputCompression(50).build(),
                ImageGenerationOptions.builder().outputFormat("webp").outputCompression(101).build(),
                ImageGenerationOptions.builder().seed(0L).build(),
                ImageGenerationOptions.builder().promptExtend(false).build())) {
            assertThrows(ImageGenerationException.class, () -> client.generate(openai, "draw", options, null), options.toString());
        }
        var qwen = ImageGenerationClientTest.config("qwen", "https://example.test");
        for (var options : List.of(
                ImageGenerationOptions.builder().n(7).build(),
                ImageGenerationOptions.builder().seed(-1L).build(),
                ImageGenerationOptions.builder().seed(2147483648L).build(),
                ImageGenerationOptions.builder().size("256x256").build(),
                ImageGenerationOptions.builder().size("4096x2048").build(),
                ImageGenerationOptions.builder().quality("high").build(),
                ImageGenerationOptions.builder().promptExtend(false).enableThinking(true).build(),
                ImageGenerationOptions.builder().promptExtendMode("impossible").build())) {
            assertThrows(ImageGenerationException.class, () -> client.generate(qwen, "draw", options, null), options.toString());
        }
        verifyNoInteractions(http);
    }
}
