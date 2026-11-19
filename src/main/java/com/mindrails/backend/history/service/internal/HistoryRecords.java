package com.mindrails.backend.history.service.internal;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.history.model.HistoryRecord;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.ChatMessageDeserializer;
import dev.langchain4j.data.message.ChatMessageSerializer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import dev.langchain4j.service.Result;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pure builders for immutable archive entries. Accumulated data belongs to the archive. */
public final class HistoryRecords {
    private static final JsonMapper JSON = JsonMapper.builder().build();
    private HistoryRecords() {}

    public static HistoryRecord start(InvocationParameters parameters, String input) {
        var metadata=new LinkedHashMap<String,Object>();
        if (parameters.containsKey(LlmClient.ATTACHMENTS)) {
            metadata.put("attachments", parameters.get(LlmClient.ATTACHMENTS));
        }
        if (parameters.containsKey(LlmClient.COMMUNICATION_SOURCE)) {
            metadata.put("communication", parameters.get(LlmClient.COMMUNICATION_SOURCE));
            metadata.put("input", input);
        }
        return entry(parameters).messages(ChatMessageSerializer.messagesToJson(List.of(UserMessage.from(input)))).metadata(JSON.writeValueAsString(metadata)).build();
    }

    public static HistoryRecord message(InvocationContext context, ChatMessage message) {
        if (!recorded(context)) return null;
        return entry(context.invocationParameters()).invocationId(context.invocationId())
                .messages(ChatMessageSerializer.messagesToJson(List.of(message))).metadata("{\"clearPartial\":true}").build();
    }

    public static HistoryRecord response(InvocationContext context, ChatResponse response) {
        if (!recorded(context)) return null;
        var metadata = usage(response.tokenUsage());
        if (response.finishReason() != null) metadata.put("finishReason", response.finishReason().name());
        metadata.put("clearPartial", true);
        return message(context, response.aiMessage()).toBuilder().metadata(JSON.writeValueAsString(metadata)).build();
    }

    public static HistoryRecord metadata(InvocationContext context, String name, Object value) {
        return !recorded(context) ? null : entry(context.invocationParameters()).invocationId(context.invocationId())
                .metadata(JSON.writeValueAsString(Map.of(name, value))).build();
    }

    public static HistoryRecord partial(InvocationParameters parameters, String name, Object value) {
        return entry(parameters).metadata(JSON.writeValueAsString(Map.of("partial", Map.of(name, value)))).build();
    }

    public static HistoryRecord completed(InvocationParameters parameters, Result<?> result) {
        return finish(parameters, HistoryRecord.Status.COMPLETED, result.content(), result.tokenUsage(),
                result.finishReason() == null ? null : result.finishReason().name(), null, null);
    }

    public static HistoryRecord completed(InvocationParameters parameters, ChatResponse response) {
        return finish(parameters, HistoryRecord.Status.COMPLETED, response.aiMessage().text(), response.tokenUsage(),
                response.finishReason() == null ? null : response.finishReason().name(), null, null);
    }

    public static HistoryRecord failed(InvocationParameters parameters, String code, String message) {
        return finish(parameters, HistoryRecord.Status.FAILED, null, null, null, code, message);
    }

    public static HistoryRecord failed(InvocationParameters parameters, String code, String message,
            String reason, String detail) {
        var record = failed(parameters, code, message);
        var error = new LinkedHashMap<String, String>();
        error.put("code", code);
        error.put("message", message == null ? "" : message);
        if (reason != null) error.put("reason", reason);
        if (detail != null) error.put("detail", detail);
        return record.toBuilder().metadata(JSON.writeValueAsString(Map.of("error", error))).build();
    }

    public static HistoryRecord cancelled(InvocationParameters parameters, String reason) {
        return finish(parameters, HistoryRecord.Status.CANCELLED, null, null, null, "RUN_CANCELLED", reason);
    }

    public static HistoryRecord finish(InvocationParameters parameters, HistoryRecord.Status status,
            Object result, TokenUsage tokenUsage, String finishReason, String code, String message) {
        var metadata = usage(tokenUsage);
        if (result != null) metadata.put("result", result);
        if (finishReason != null) metadata.put("finishReason", finishReason);
        if (code != null) metadata.put("error", Map.of("code", code, "message", message == null ? "" : message));
        return entry(parameters).status(status).finishedAt(Instant.now()).metadata(JSON.writeValueAsString(metadata)).build();
    }

    private static boolean recorded(InvocationContext context) {
        return context != null && context.invocationParameters() != null
                && context.invocationParameters().get(LlmClient.RUN_ID) != null;
    }

    private static HistoryRecord.HistoryRecordBuilder entry(InvocationParameters parameters) {
        String transport = parameters.get(LlmClient.TRANSPORT);
        return HistoryRecord.builder().id(parameters.get(LlmClient.RUN_ID)).ownerId(parameters.get(LlmClient.OWNER_ID)).agentId(parameters.get(LlmClient.AGENT_ID))
                .sessionId(parameters.get(LlmClient.SESSION_ID)).taskId(parameters.get(LlmClient.TASK_ID)).memoryId(parameters.get(LlmClient.MEMORY_ID))
                .providerId(parameters.get(LlmClient.PROVIDER_ID))
                .requestId(parameters.get(LlmClient.REQUEST_ID))
                .transport("WEBSOCKET".equals(transport) ? HistoryRecord.Transport.STREAM : HistoryRecord.Transport.SYNC)
                .status(HistoryRecord.Status.RUNNING).messages("[]").metadata("{}")
                .startedAt(parameters.get(LlmClient.STARTED_AT)).schemaVersion(1);
    }

    private static Map<String, Object> usage(TokenUsage usage) {
        var result = new LinkedHashMap<String, Object>();
        if (usage != null) {
            var tokens = new LinkedHashMap<String, Object>();
            tokens.put("inputTokens", usage.inputTokenCount());
            tokens.put("outputTokens", usage.outputTokenCount());
            tokens.put("totalTokens", usage.totalTokenCount());
            result.put("usage", tokens);
        }
        return result;
    }

    /** Called atomically by the store. Terminal records are immutable, including under late callbacks. */
    @SuppressWarnings("unchecked")
    public static HistoryRecord merge(HistoryRecord previous, HistoryRecord entry) {
        if (previous == null) return entry;
        if (previous.status() != HistoryRecord.Status.RUNNING) return previous;
        var messages = new ArrayList<>(ChatMessageDeserializer.messagesFromJson(previous.messages()));
        var metadata = JSON.readValue(previous.metadata(), new TypeReference<Map<String, Object>>() {});
        var update = JSON.readValue(entry.metadata(), new TypeReference<Map<String, Object>>() {});
        var added = new ArrayList<>(ChatMessageDeserializer.messagesFromJson(entry.messages()));
        if (metadata.get("partial") instanceof Map<?, ?> partial) {
            for (int i = 0; i < added.size(); i++) {
                if (added.get(i) instanceof AiMessage ai) {
                    if ((ai.text() == null || ai.text().isEmpty()) && partial.get("answer") instanceof String answer) ai = ai.withText(answer);
                    if ((ai.thinking() == null || ai.thinking().isEmpty()) && partial.get("thinking") instanceof String thinking) ai = ai.toBuilder().thinking(thinking).build();
                    added.set(i, ai);
                }
            }
        }
        if (Boolean.TRUE.equals(update.remove("clearPartial"))) {
            // Completed provider data replaces fragments for this round.
            metadata.remove("partial");
        }
        messages.addAll(added);
        for (var item : update.entrySet()) {
            if (item.getKey().equals("partial") && item.getValue() instanceof Map<?, ?> fragments) {
                @SuppressWarnings("unchecked")
                var partial = new LinkedHashMap<>((Map<String, Object>) metadata.getOrDefault("partial", Map.of()));
                fragments.forEach((key, value) -> {
                    String field = key.toString();
                    if (value instanceof String text) partial.merge(field, text, (a, b) -> a.toString() + b);
                    else if (field.equals("toolCalls") && value instanceof List<?> fragmentsList) {
                        var tools = new LinkedHashMap<Object, Map<String, Object>>();
                        for (Object fragment : (List<?>) partial.getOrDefault(field, List.of())) {
                            var tool = new LinkedHashMap<String, Object>((Map<String, Object>) fragment);
                            tools.put(tool.get("index"), tool);
                        }
                        for (Object fragment : fragmentsList) {
                            var delta = (Map<String, Object>) fragment;
                            var tool = tools.computeIfAbsent(delta.get("index"), ignored -> new LinkedHashMap<>());
                            delta.forEach((name, part) -> {
                                if (name.equals("arguments")) tool.merge(name, part, (a, b) -> a.toString() + b);
                                else tool.put(name, part);
                            });
                        }
                        partial.put(field, List.copyOf(tools.values()));
                    } else partial.put(field, value);
                });
                metadata.put("partial", partial);
            } else if ((item.getKey().equals("mcpTools") || item.getKey().equals("skills")) && item.getValue() instanceof Map<?, ?> sources) {
                var all = new LinkedHashMap<String, Object>();
                if (metadata.get(item.getKey()) instanceof Map<?, ?> old) old.forEach((key, value) -> all.put(key.toString(), value));
                sources.forEach((key, value) -> all.put(key.toString(), value));
                metadata.put(item.getKey(), all);
            } else metadata.put(item.getKey(), item.getValue());
        }
        return previous.toBuilder().invocationId(entry.invocationId() == null ? previous.invocationId() : entry.invocationId())
                .status(entry.status()).finishedAt(entry.finishedAt()).messages(ChatMessageSerializer.messagesToJson(messages))
                .metadata(JSON.writeValueAsString(metadata)).build();
    }
}
