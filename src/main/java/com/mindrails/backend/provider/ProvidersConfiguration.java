package com.mindrails.backend.provider;

import com.openai.models.Reasoning;
import com.openai.models.ReasoningEffort;

import dev.langchain4j.community.model.dashscope.QwenChatModel;
import dev.langchain4j.community.model.dashscope.QwenStreamingChatModel;
import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.anthropic.AnthropicStreamingChatModel;
import dev.langchain4j.model.googleai.GeminiThinkingConfig;
import dev.langchain4j.model.googleai.GoogleAiGeminiChatModel;
import dev.langchain4j.model.googleai.GoogleAiGeminiStreamingChatModel;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesChatModel;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesChatRequestParameters;
import dev.langchain4j.model.openaiofficial.OpenAiOfficialResponsesStreamingChatModel;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;


import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Both writable catalogs share validation and runtime model construction. */
@Configuration
public class ProvidersConfiguration {

    @Bean
    public ProviderRegistry providerRegistry(
            Environment env,
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlProviderRegistry(jdbc, env, this);
    }

    /** Compatibility catalog for existing consumers; production lookups use ProviderRegistry. */
    @Bean
    public List<Provider> providers(ProviderRegistry registry) {
        return registry.list(0, Integer.MAX_VALUE);
    }

    List<Provider> loadDefinitions(
            List<ProviderDefinition> definitions, String defaultName) {
        if (definitions == null || definitions.isEmpty())
            throw new IllegalArgumentException("At least one provider is required");
        requireText(defaultName, "defaultProvider");
        List<Provider> providers = new ArrayList<>();
        Set<String> names = new HashSet<>();
        Set<UUID> ids = new HashSet<>();
        for (ProviderDefinition definition : definitions) {
            if (definition == null) {
                throw new IllegalArgumentException("Provider entries must not be null");
            }
            requireText(definition.name(), "Provider name");
            if (!names.add(definition.name())) {
                throw new IllegalArgumentException("Duplicate provider name: " + definition.name());
            }
            Provider provider = buildProvider(definition);
            if (!ids.add(provider.getId()))
                throw new IllegalArgumentException("Duplicate provider ID");
            providers.add(provider);
        }
        Provider defaultProvider =
                providers.stream()
                        .filter(provider -> provider.getName().equals(defaultName))
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new IllegalArgumentException(
                                                "defaultProvider must reference a configured"
                                                        + " provider"));
        providers.remove(defaultProvider);
        providers.addFirst(defaultProvider);
        return providers;
    }

    Provider buildProvider(ProviderDefinition definition) {
        requireText(definition.modelName(), "Model name for " + definition.name());
        requireText(definition.chatModel(), "Integration mode for " + definition.name());
        validateTokenLimits(definition);
        Provider provider =
                Provider.builder()
                        .id(
                                definition.id() != null
                                        ? definition.id()
                                        : UUID.nameUUIDFromBytes(
                                                ("mindrails:provider:" + definition.name())
                                                        .getBytes(StandardCharsets.UTF_8)))
                        .name(definition.name())
                        .integrationMode(definition.chatModel())
                        .streaming(definition.streaming() == null || definition.streaming())
                        .baseUrl(definition.baseUrl())
                        .apiKey(
                                definition.apiKey() == null || definition.apiKey().isBlank()
                                        ? "unconfigured"
                                        : definition.apiKey())
                        .modelName(definition.modelName())
                        .reasoning(definition.reasoning())
                        .thinkingLevel(definition.thinkingLevel())
                        .modelCost(definition.modelCost())
                        .contextWindow(definition.contextWindow())
                        .maxTokens(definition.maxTokens())
                        .build();
        if (definition.apiKey() != null && !definition.apiKey().isBlank())
            ProviderSecrets.validate(definition.apiKey());
        switch (definition.chatModel()) {
            case "OpenAi" -> configureOpenAi(provider);
            case "OpenAiCompiable" -> configureOpenAiCompatible(provider);
            case "Anthropic" -> configureAnthropic(provider);
            case "GoogleAiGemini" -> configureGemini(provider);
            case "Qwen" -> configureQwen(provider);
            default ->
                    throw new IllegalArgumentException(
                            "Unsupported integration mode for " + definition.name());
        }
        if (definition.apiKey() == null || definition.apiKey().isBlank()) {
            provider.setApiKey(null);
            provider.setChatModel(null);
            provider.setStreamingChatModel(null);
        }
        return provider;
    }

    private void configureOpenAi(Provider provider) {
        String effort =
                reasoningEffort(provider, Set.of("none", "low", "medium", "high", "xhigh", "max"));
        var parameters =
                OpenAiOfficialResponsesChatRequestParameters.builder()
                        .reasoningEffort(effort == null ? null : ReasoningEffort.of(effort))
                        .reasoningSummary("none".equals(effort) ? null : Reasoning.Summary.AUTO)
                        .maxOutputTokens(provider.getMaxTokens())
                        // Chat memory replays history locally, including reasoning between tool calls.
                        .store(false)
                        .include(List.of("reasoning.encrypted_content"))
                        .build();
        if (provider.getStreaming()) {
            var builder =
                    OpenAiOfficialResponsesStreamingChatModel.builder()
                            .apiKey(provider.getApiKey())
                            .modelName(provider.getModelName())
                            .defaultRequestParameters(parameters);
            if (provider.getBaseUrl() != null) builder.baseUrl(provider.getBaseUrl());
            provider.setStreamingChatModel(builder.build());
        } else {
            var builder =
                    OpenAiOfficialResponsesChatModel.builder()
                            .apiKey(provider.getApiKey())
                            .modelName(provider.getModelName())
                            .defaultRequestParameters(parameters);
            if (provider.getBaseUrl() != null) builder.baseUrl(provider.getBaseUrl());
            provider.setChatModel(builder.build());
        }
    }

    private void configureOpenAiCompatible(Provider provider) {
        requireText(provider.getBaseUrl(), "Base URL for " + provider.getName());
        boolean deepSeek =
                "api.deepseek.com".equalsIgnoreCase(URI.create(provider.getBaseUrl()).getHost());

        // Deepseek uses a different set of reasoning-effort values than the OpenAI default
        String effort =
                reasoningEffort(
                        provider,
                        deepSeek
                                ? Set.of("none", "low", "high", "max")
                                : Set.of(
                                        "none", "minimal", "low", "medium", "high", "xhigh",
                                        "max"));

        // Deepseek requires thinking mode to be toggled via a custom "thinking" parameter;
        // other OpenAI-compatible providers don't need this
        Map<String, Object> parameters =
                deepSeek && provider.getReasoning() != null
                        ? Map.of(
                                "thinking",
                                Map.of("type", provider.getReasoning() ? "enabled" : "disabled"))
                        : Map.of();

        // sendThinking: Deepseek's API requires reasoning_content from prior turns to be
        // echoed back in follow-up requests; other providers don't use this field name
        if (provider.getStreaming()) {
            provider.setStreamingChatModel(
                    OpenAiStreamingChatModel.builder()
                            .apiKey(provider.getApiKey())
                            .baseUrl(provider.getBaseUrl())
                            .modelName(provider.getModelName())
                            .maxTokens(provider.getMaxTokens())
                            .reasoningEffort(effort)
                            .returnThinking(true)
                            .sendThinking(deepSeek)
                            .customParameters(parameters)
                            .build());
        } else {
            provider.setChatModel(
                    OpenAiChatModel.builder()
                            .apiKey(provider.getApiKey())
                            .baseUrl(provider.getBaseUrl())
                            .modelName(provider.getModelName())
                            .maxTokens(provider.getMaxTokens())
                            .reasoningEffort(effort)
                            .returnThinking(true)
                            .sendThinking(deepSeek)
                            .customParameters(parameters)
                            .build());
        }
    }

    private void configureAnthropic(Provider provider) {
        validateLevel(provider, Set.of("low", "medium", "high", "xhigh", "max"));
        String thinkingType =
                provider.getReasoning() == null
                        ? null
                        : provider.getReasoning() ? "adaptive" : "disabled";
        Map<String, Object> parameters =
                provider.getThinkingLevel() == null
                        ? Map.of()
                        : Map.of("output_config", Map.of("effort", provider.getThinkingLevel()));
        if (provider.getStreaming()) {
            var builder =
                    AnthropicStreamingChatModel.builder()
                            .apiKey(provider.getApiKey())
                            .modelName(provider.getModelName())
                            .maxTokens(provider.getMaxTokens())
                            .thinkingType(thinkingType)
                            .returnThinking(true)
                            .sendThinking(true)
                            .customParameters(parameters);
            if (provider.getBaseUrl() != null) builder.baseUrl(provider.getBaseUrl());
            provider.setStreamingChatModel(builder.build());
        } else {
            var builder =
                    AnthropicChatModel.builder()
                            .apiKey(provider.getApiKey())
                            .modelName(provider.getModelName())
                            .maxTokens(provider.getMaxTokens())
                            .thinkingType(thinkingType)
                            .returnThinking(true)
                            .sendThinking(true)
                            .customParameters(parameters);
            if (provider.getBaseUrl() != null) builder.baseUrl(provider.getBaseUrl());
            provider.setChatModel(builder.build());
        }
    }

    private void configureGemini(Provider provider) {
        validateLevel(provider, Set.of("minimal", "low", "medium", "high"));
        if (Boolean.FALSE.equals(provider.getReasoning()) && provider.getThinkingLevel() != null) {
            throw new IllegalArgumentException(
                    "Disabled reasoning conflicts with thinkingLevel for " + provider.getName());
        }
        GeminiThinkingConfig thinking = null;
        if (provider.getReasoning() != null || provider.getThinkingLevel() != null) {
            var builder =
                    GeminiThinkingConfig.builder()
                            .includeThoughts(true)
                            .thinkingLevel(provider.getThinkingLevel());
            if (Boolean.FALSE.equals(provider.getReasoning())) builder.thinkingBudget(0);
            thinking = builder.build();
        }
        if (provider.getStreaming()) {
            var builder =
                    GoogleAiGeminiStreamingChatModel.builder()
                            .apiKey(provider.getApiKey())
                            .modelName(provider.getModelName())
                            .maxOutputTokens(provider.getMaxTokens())
                            .thinkingConfig(thinking)
                            .returnThinking(true)
                            .sendThinking(true);
            if (provider.getBaseUrl() != null) builder.baseUrl(provider.getBaseUrl());
            provider.setStreamingChatModel(builder.build());
        } else {
            var builder =
                    GoogleAiGeminiChatModel.builder()
                            .apiKey(provider.getApiKey())
                            .modelName(provider.getModelName())
                            .maxOutputTokens(provider.getMaxTokens())
                            .thinkingConfig(thinking)
                            .returnThinking(true)
                            .sendThinking(true);
            if (provider.getBaseUrl() != null) builder.baseUrl(provider.getBaseUrl());
            provider.setChatModel(builder.build());
        }
    }

    private void configureQwen(Provider provider) {
        if (provider.getReasoning() != null || provider.getThinkingLevel() != null) {
            throw new IllegalArgumentException(
                    "The native Qwen wrapper does not expose reasoning controls; "
                            + "use OpenAiCompiable for "
                            + provider.getName());
        }
        if (provider.getStreaming()) {
            var builder =
                    QwenStreamingChatModel.builder()
                            .apiKey(provider.getApiKey())
                            .modelName(provider.getModelName())
                            .maxTokens(provider.getMaxTokens());
            if (provider.getBaseUrl() != null) builder.baseUrl(provider.getBaseUrl());
            provider.setStreamingChatModel(builder.build());
        } else {
            var builder =
                    QwenChatModel.builder()
                            .apiKey(provider.getApiKey())
                            .modelName(provider.getModelName())
                            .maxTokens(provider.getMaxTokens());
            if (provider.getBaseUrl() != null) builder.baseUrl(provider.getBaseUrl());
            provider.setChatModel(builder.build());
        }
    }

    private String reasoningEffort(Provider provider, Set<String> levels) {
        validateLevel(provider, levels);
        String level = provider.getThinkingLevel();
        if (Boolean.FALSE.equals(provider.getReasoning())) {
            if (level != null && !level.equals("none")) {
                throw new IllegalArgumentException(
                        "Disabled reasoning conflicts with thinkingLevel for "
                                + provider.getName());
            }
            return "none";
        }
        if (Boolean.TRUE.equals(provider.getReasoning()) && "none".equals(level)) {
            throw new IllegalArgumentException(
                    "Enabled reasoning conflicts with thinkingLevel for " + provider.getName());
        }
        return level;
    }

    private void validateLevel(Provider provider, Set<String> levels) {
        if (provider.getThinkingLevel() != null && !levels.contains(provider.getThinkingLevel())) {
            throw new IllegalArgumentException(
                    "Unsupported thinkingLevel for " + provider.getName());
        }
    }

    private void validateTokenLimits(ProviderDefinition definition) {
        if (definition.contextWindow() != null && definition.contextWindow() <= 0
                || definition.maxTokens() != null && definition.maxTokens() <= 0
                || definition.contextWindow() != null
                        && definition.maxTokens() != null
                        && definition.maxTokens() > definition.contextWindow()) {
            throw new IllegalArgumentException("Invalid token limits for " + definition.name());
        }
    }

    private void requireText(String value, String field) {
        if (value == null || value.isBlank())
            throw new IllegalArgumentException(field + " must not be blank");
    }

}
