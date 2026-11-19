package com.mindrails.backend.provider;

import com.fasterxml.jackson.annotation.JsonIgnore;

import dev.langchain4j.model.chat.ChatModel;
import dev.langchain4j.model.chat.StreamingChatModel;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Getter
@Setter
@Builder(toBuilder = true)
@NoArgsConstructor
@AllArgsConstructor
public class Provider {

    /** Stable ID; derived from the configured name when a catalog entry omits its ID. */
    private UUID id;

    /** Unique configuration name, referenced by defaultProvider and other objects. */
    private String name;

    private String integrationMode;

    /**
     * LC4J synchronous ChatModel instance constructed when loading the configuration. The JSON
     * field of the same name specifies the integration mode: Anthropic, OpenAiCompiable, Qwen,
     * GoogleAiGemini or OpenAi. OpenAiCompiable uses the OpenAI-compatible protocol; OpenAi uses
     * the OpenAI Official SDK with the Responses API. Populated only when streaming is false.
     */
    @JsonIgnore private ChatModel chatModel;

    /** LC4J streaming model instance, populated only when streaming is true. */
    @JsonIgnore private StreamingChatModel streamingChatModel;

    /**
     * Whether to stream responses; configuration defaults to true. False returns a complete
     * response.
     */
    private Boolean streaming;

    /** Base URL of the model service API, for example https://api.deepseek.com. */
    private String baseUrl;

    /** Decrypted runtime credential, excluded from every JSON response. */
    @JsonIgnore private String apiKey;

    public boolean isHasApiKey() {
        return apiKey != null && !apiKey.isBlank();
    }

    /** Model ID recognized by the service, for example gpt-6-luna or deepseek-flash. */
    private String modelName;

    /**
     * Whether to request reasoning; null uses the model default. Disabling it depends on the model.
     */
    private Boolean reasoning;

    /**
     * Requested reasoning effort using the model's native level name; null uses the model default.
     * Each model supports a subset of none, minimal, low, medium, high, xhigh and max; values must
     * be validated against the model. Mapped to the provider's reasoning_effort, effort or
     * thinkingLevel parameter when loading.
     */
    private String thinkingLevel;

    /**
     * Model cost metric; currency, token unit and representation of input/output costs are pending.
     */
    private BigDecimal modelCost;

    /**
     * Model context capacity in tokens, used to plan input history and output budgets. Setting this
     * value does not increase the model's capacity. Examples: 1050000 for GPT-6 Luna; 1000000 for
     * DeepSeek Flash's documented 1M context.
     */
    private Integer contextWindow;

    /**
     * Maximum generated tokens per request, typically including reasoning and final answer tokens
     * for reasoning models. This application output budget must not exceed the model's output limit
     * and must fit within the context capacity together with the input. Examples use 16384; null
     * omits an explicit limit and uses the model service's default.
     */
    private Integer maxTokens;
}
