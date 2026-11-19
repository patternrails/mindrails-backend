package com.mindrails.backend.image;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/** Per-call output options; credentials, endpoint and model stay in the tested Provider. */
@lombok.Builder(toBuilder = true)
public record ImageGenerationOptions(Integer width, Integer height, String size, Integer n,
        String quality, String background, String outputFormat, Integer outputCompression,
        String moderation, String negativePrompt, Long seed, Boolean promptExtend,
        String promptExtendMode, Boolean enableThinking, Boolean watermark) {
    public static ImageGenerationOptions defaults() { return builder().build(); }
    public int count() { return n == null ? 1 : n; }

    Map<String, Object> request(ImageProvider.Info provider, String prompt) {
        if (prompt == null || prompt.isBlank() || prompt.length() > 32000)
            throw invalid("Prompt must contain 1..32000 characters");
        boolean qwen = provider.providerName().equals("qwen");
        if (count() < 1 || count() > (qwen ? 6 : 10))
            throw invalid("n must be between 1 and " + (qwen ? 6 : 10));
        String resolvedSize = size == null ? provider.size() : size;
        if (width != null || height != null) {
            if (width == null || height == null) throw invalid("Provide both width and height in pixels");
            String dimensions = width + "x" + height;
            if (size != null && !size.equals(dimensions)) throw invalid("size conflicts with width and height");
            resolvedSize = dimensions;
        }
        validateSize(resolvedSize, provider);
        var body = new LinkedHashMap<String, Object>();
        body.put("model", provider.model()); body.put("prompt", prompt);
        body.put("size", resolvedSize); body.put("n", count());
        if (qwen) {
            if (quality != null || background != null || outputFormat != null || outputCompression != null || moderation != null)
                throw invalid("quality, background, output_format, output_compression and moderation are OpenAI-only parameters");
            if (seed != null && (seed < 0 || seed > 2147483647L)) throw invalid("seed must be between 0 and 2147483647");
            if (negativePrompt != null && (negativePrompt.length() > 32000 || provider.model().startsWith("qwen-image-2")))
                throw invalid("negative_prompt requires Qwen Image 3.x and at most 32000 characters");
            boolean extend = promptExtend == null ? provider.promptExtend() : promptExtend;
            if (!extend && (promptExtendMode != null || enableThinking != null))
                throw invalid("prompt_extend_mode and enable_thinking require prompt_extend=true");
            choice("prompt_extend_mode", promptExtendMode, Set.of("direct", "agent"));
            body.put("prompt_extend", extend);
            put(body, "negative_prompt", negativePrompt); put(body, "seed", seed);
            put(body, "prompt_extend_mode", promptExtendMode); put(body, "enable_thinking", enableThinking);
            put(body, "watermark", watermark);
        } else {
            if (negativePrompt != null || seed != null || promptExtend != null || promptExtendMode != null || enableThinking != null || watermark != null)
                throw invalid("negative_prompt, seed, prompt_extend, prompt_extend_mode, enable_thinking and watermark are Qwen-only parameters");
            choice("quality", quality, Set.of("auto", "low", "medium", "high", "xhigh", "max"));
            if (Set.of("xhigh", "max").contains(quality == null ? "" : quality)
                    && provider.model().startsWith("gpt-image-") && !provider.model().startsWith("gpt-image-2.5-"))
                throw invalid("xhigh/max quality requires GPT Image 2.5");
            choice("background", background, Set.of("auto", "opaque", "transparent"));
            choice("output_format", outputFormat, Set.of("png", "jpeg", "webp"));
            choice("moderation", moderation, Set.of("auto", "low"));
            if ("transparent".equals(background) && "jpeg".equals(outputFormat))
                throw invalid("Transparent backgrounds require png or webp");
            if (outputCompression != null && (outputCompression < 0 || outputCompression > 100
                    || !("jpeg".equals(outputFormat) || "webp".equals(outputFormat))))
                throw invalid("output_compression must be 0..100 and requires jpeg or webp");
            put(body, "quality", quality); put(body, "background", background);
            put(body, "output_format", outputFormat); put(body, "output_compression", outputCompression);
            put(body, "moderation", moderation);
        }
        return body;
    }

    private static void validateSize(String size, ImageProvider.Info provider) {
        if ("auto".equals(size)) return;
        if (size == null || !size.matches("[1-9][0-9]{0,3}x[1-9][0-9]{0,3}"))
            throw invalid("size must be auto or WIDTHxHEIGHT");
        var dimensions = size.split("x");
        int w = Integer.parseInt(dimensions[0]), h = Integer.parseInt(dimensions[1]);
        long pixels = (long) w * h;
        if (provider.providerName().equals("qwen")) {
            if (pixels < 512L * 512 || pixels > 2048L * 2048 || Math.max(w, h) > 8L * Math.min(w, h))
                throw invalid("Qwen size requires 512x512..2048x2048 total pixels and aspect ratio 1:8..8:1");
        } else if (w % 16 != 0 || h % 16 != 0 || Math.max(w, h) > 3840 || Math.min(w, h) > 2160
                || Math.max(w, h) > 3L * Math.min(w, h)
                || provider.model().startsWith("gpt-image-1") && !Set.of("1024x1024", "1536x1024", "1024x1536").contains(size)) {
            throw invalid("OpenAI dimensions must be multiples of 16, aspect ratio 1:3..3:1, within 3840x2160; older models require standard sizes");
        }
    }
    private static void choice(String name, String value, Set<String> allowed) {
        if (value != null && !allowed.contains(value)) throw invalid("Unsupported " + name + "; allowed: " + String.join(", ", allowed));
    }
    private static void put(Map<String, Object> body, String name, Object value) { if (value != null) body.put(name, value); }
    private static ImageGenerationException invalid(String message) { return ImageGenerationException.invalid(message); }
}
