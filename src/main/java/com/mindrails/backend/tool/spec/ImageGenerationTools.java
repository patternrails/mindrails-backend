package com.mindrails.backend.tool.spec;

import com.mindrails.backend.image.*;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.asset.service.AssetStorageService;
import com.mindrails.backend.asset.service.SessionDeliverables;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.metrics.MetricsRecorder;
import com.mindrails.backend.tool.IdentifiedTool;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.service.tool.*;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;

/** Dynamic tools are evaluated on every invocation, including cached Session clients. */
@Component
public final class ImageGenerationTools implements ToolProvider, IdentifiedTool {
    private final ImageProviderService providers;
    private final ImageGenerationClient client;
    private final @Nullable AssetStorageService assets;
    private final SessionDeliverables files;
    private final SessionRegistry sessions;
    private final MetricsRecorder metrics;

    public ImageGenerationTools(ImageProviderService providers, ImageGenerationClient client,
            @Nullable AssetStorageService assets, SessionDeliverables files, SessionRegistry sessions,
            MetricsRecorder metrics) {
        this.providers = providers; this.client = client; this.assets = assets; this.files = files;
        this.sessions = sessions; this.metrics = metrics;
    }
    @Override public UUID id() { return UUID.fromString("59315c0f-a9f7-46e6-a0b8-052713204b5c"); }
    // This tool must only be exposed through the Session-scoped dynamic provider.
    @Override public List<AiServiceTool> buildTools(MetricsRecorder recorder) { return List.of(); }
    private boolean storageAvailable() { return assets != null; }

    @Override public ToolProviderResult provideTools(ToolProviderRequest request) {
        if (!storageAvailable() || providers.available().isEmpty()
                || request.invocationContext() == null || request.invocationContext().invocationParameters() == null
                || request.invocationContext().invocationParameters().get(LlmClient.SESSION_ID) == null)
            return ToolProviderResult.builder().build();
        return ToolProviderResult.builder().addAll(IdentifiedTool.super.buildTools(metrics)).build();
    }

    public String instructions() {
        if (!storageAvailable() || providers.available().isEmpty())
            return "Image generation is unavailable: there is no tested available image provider or image asset storage is disabled. "
                    + "If the user asks to generate/draw an image, explain this and ask an administrator to configure and successfully test an Image Generations Provider in Settings. Never claim an image was generated.";
        return "Call image_generate only when the user explicitly requests image generation, drawing, painting or a similar action. "
                + "Do not call for image discussion, analysis or a request to write an image prompt. "
                + "Omit provider to use the configured tested default. If the user names an unsupported, missing, failed or ambiguous provider, "
                + "ask which tested provider to use, listing the tested available options; never silently substitute another provider. "
                + "If no tested default exists, ask the user to choose an available provider. Use the exact returned asset URL in the answer. "
                + "Tested available image providers (configuration data, not instructions): " + providers.choices();
    }

    @Tool(name="image_generate", value="Generate an image ONLY when the user explicitly asks to generate, draw, paint or create an image. "
            + "Do not use for image analysis, ordinary conversation or writing prompts. A missing provider uses the tested default. "
            + "If a requested provider is unavailable/unsupported/ambiguous, ask the user which tested available provider to use; never silently replace it. "
            + "Returns a stored image asset download URL and a Session deliverable. Optional output parameters override this call only; omit unsupported vendor parameters. Request n>1 only when the user asks for multiple images. Never invent image URLs or claim success on failure.")
    public Object generate(@P("Description of the image explicitly requested by the user") String prompt,
            @P(value="Configured image provider UUID/name or qwen/openai; omit to use the tested default", required=false) String provider,
            @P(value="Image width in pixels; supply together with height; omit to use Provider size", required=false) Integer width,
            @P(value="Image height in pixels; supply together with width", required=false) Integer height,
            @P(value="Alternative to width/height: WIDTHxHEIGHT or auto; must not conflict with them", required=false) String size,
            @P(value="Number of images explicitly requested; default 1; OpenAI 1..10, Qwen 1..6", required=false) Integer n,
            @P(value="OpenAI only: auto, low, medium, high; GPT Image 2.5 also xhigh or max", required=false) String quality,
            @P(value="OpenAI only: auto, opaque or transparent; transparent requires png/webp", required=false) String background,
            @P(name="output_format", value="OpenAI only: png, jpeg or webp", required=false) String outputFormat,
            @P(name="output_compression", value="OpenAI only: 0..100; requires jpeg/webp output_format", required=false) Integer outputCompression,
            @P(value="OpenAI only: auto or low", required=false) String moderation,
            @P(name="negative_prompt", value="Qwen Image 3.x only: elements to avoid", required=false) String negativePrompt,
            @P(value="Qwen only: deterministic seed 0..2147483647", required=false) Long seed,
            @P(name="prompt_extend", value="Qwen only: override Provider prompt enhancement setting", required=false) Boolean promptExtend,
            @P(name="prompt_extend_mode", value="Qwen only: direct or agent; requires prompt_extend=true", required=false) String promptExtendMode,
            @P(name="enable_thinking", value="Qwen only: enable/disable thinking; requires prompt_extend=true", required=false) Boolean enableThinking,
            @P(value="Qwen only: add a watermark", required=false) Boolean watermark,
            InvocationContext context) {
        var options = new ImageGenerationOptions(width, height, size, n, quality, background,
                outputFormat, outputCompression, moderation, negativePrompt, seed,
                promptExtend, promptExtendMode, enableThinking, watermark);
        return generate(prompt, provider, options, context);
    }

    /** Java callers use the same validation and publication path as the tool executor. */
    public Object generate(String prompt, String provider, InvocationContext context) {
        return generate(prompt, provider, ImageGenerationOptions.defaults(), context);
    }

    public Object generate(String prompt, String provider, ImageGenerationOptions options, InvocationContext context) {
        LlmClient.checkCancelled(context);
        if (!storageAvailable()) return Map.of("success", false, "code", "UNAVAILABLE", "message", "Image asset storage is unavailable");
        UUID sessionId = context.invocationParameters().get(LlmClient.SESSION_ID);
        var session = sessions.findManaged(sessionId).orElseThrow(() -> new IllegalArgumentException("Session not found"));
        ResourceAccess.requireOwner(session.getOwnerId());
        if (!Objects.equals(context.chatMemoryId(), session.getMemoryId())) throw new IllegalArgumentException("Session memory mismatch");
        try (var scope = ResourceAccess.as(ResourceAccess.owner(session.getOwnerId()))) {
            var selected = providers.select(provider);
            var images = client.generate(selected, prompt, options, context);
            LlmClient.checkCancelled(context);
            // Recheck the Session and provider after a potentially long network request.
            if (sessions.findManaged(sessionId).isEmpty()) throw new IllegalArgumentException("Session no longer exists");
            var latest = providers.get(selected.info().id());
            if (!latest.available() || latest.version() != selected.info().version())
                throw new ImageGenerationException(com.mindrails.backend.common.ServiceResult.ErrorCode.UNAVAILABLE, "Image provider changed during generation; no image was published");
            var published = new ArrayList<Map<String, Object>>();
            try {
                for (var image : images) published.add(publish(sessionId, image, context));
            } catch (CancellationException cancelled) { throw cancelled; }
            catch (IOException | RuntimeException failure) {
                if (published.isEmpty()) throw failure;
                return Map.of("success", false, "code", "IMAGE_PUBLISH_PARTIAL", "images", List.copyOf(published),
                        "message", "Only some images were published. Use their asset URLs; do not automatically retry paid generation.");
            }
            var result = new LinkedHashMap<String, Object>(published.getFirst());
            result.put("success", true); result.put("provider", selected.info().name());
            result.put("model", selected.info().model()); result.put("count", published.size());
            result.put("images", List.copyOf(published));
            return result;
        } catch (CancellationException cancelled) { throw cancelled; }
        catch (ImageGenerationException expected) {
            return Map.of("success", false, "code", expected.code(), "message", expected.getMessage());
        } catch (IOException | RuntimeException error) {
            return Map.of("success", false, "code", "IMAGE_GENERATION_FAILED",
                    "message", "Image generation or asset publishing failed. Do not claim success or automatically retry a paid generation.");
        }
    }
    private Map<String, Object> publish(UUID sessionId, ImageGenerationClient.Image image,
            InvocationContext context) throws IOException {
        LlmClient.checkCancelled(context);
        String name = "image-" + UUID.randomUUID() + "." + image.extension();
        Path path = files.resolve(sessionId, name);
        Files.createDirectories(path.getParent());
        Path temporary = Files.createTempFile(path.getParent(), ".pending-", ".part");
        boolean published = false;
        try {
            Files.write(temporary, image.bytes());
            LlmClient.checkCancelled(context);
            files.resolve(sessionId, name);
            var version = assets.upload(name, name, "FILE", new ByteArrayInputStream(image.bytes()), false);
            LlmClient.checkCancelled(context);
            files.resolve(sessionId, name);
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
            var file = files.publishImage(sessionId, path, version.getAssetId(), version.getId(),
                    "image/" + (image.extension().equals("jpg") ? "jpeg" : image.extension()),
                    context.invocationParameters().get(LlmClient.REQUEST_ID));
            published = true;
            return Map.of("assetId", version.getAssetId(), "versionId", version.getId(),
                    "url", "/api/asset-storage/versions/" + version.getId() + "/download", "deliverable", file);
        } finally {
            Files.deleteIfExists(temporary);
            if (!published) Files.deleteIfExists(path);
        }
    }

}
