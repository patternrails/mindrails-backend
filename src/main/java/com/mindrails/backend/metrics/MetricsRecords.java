package com.mindrails.backend.metrics;

import com.mindrails.backend.agent.model.LlmClient;
import dev.langchain4j.invocation.InvocationContext;
import dev.langchain4j.invocation.InvocationParameters;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.output.TokenUsage;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/** Pure builders and merge functions; no live runs, observations or callbacks are retained here. */
public final class MetricsRecords {
    private static final UUID INSTANCE_ID = UUID.randomUUID();
    private MetricsRecords() {}

    public static MetricsSnapshot start(InvocationParameters parameters) {
        Instant startedAt = parameters.get(LlmClient.STARTED_AT);
        return MetricsSnapshot.builder().runId(parameters.get(LlmClient.RUN_ID)).instanceId(INSTANCE_ID).ownerId(parameters.get(LlmClient.OWNER_ID))
                .taskId(parameters.get(LlmClient.TASK_ID)).agentId(parameters.get(LlmClient.AGENT_ID)).sessionId(parameters.get(LlmClient.SESSION_ID)).memoryId(parameters.get(LlmClient.MEMORY_ID))
                .requestId(parameters.get(LlmClient.REQUEST_ID)).providerId(parameters.get(LlmClient.PROVIDER_ID))
                .providerName(text(parameters.get(LlmClient.PROVIDER_NAME)))
                .modelName(text(parameters.get(LlmClient.MODEL_NAME))).sessionVersion(parameters.get(LlmClient.SESSION_VERSION))
                .transport(parameters.get(LlmClient.TRANSPORT)).configuredSkillIds(parameters.get(LlmClient.SKILL_IDS))
                .status("RUNNING").startedAt(startedAt).lastObservedAt(Instant.now()).revision(1)
                .knownInputTokens(0L).knownOutputTokens(0L).knownTotalTokens(0L).usageCoverage("NOT_APPLICABLE").build();
    }

    public static MetricsSnapshot finish(InvocationParameters parameters, String status, String reason,
            String code, TokenUsage aggregate) {
        var value = start(parameters);
        var end = Instant.now();
        return value.toBuilder().status(status).endedAt(end).lastObservedAt(end)
                .durationMs(elapsed(value.startedAt(), end)).terminalReason(text(reason)).errorCode(text(code))
                .aggregateUsageReported(usage(aggregate)).build();
    }

    public static MetricsSnapshot modelRequested(InvocationContext context, String model) {
        return model(context, UUID.randomUUID(), Instant.now(), model, null, false);
    }

    public static MetricsSnapshot modelResponded(InvocationContext context, ChatResponse response) {
        return model(context, null, Instant.now(), null, response, false);
    }

    public static MetricsSnapshot modelFailed(InvocationContext context) {
        return model(context, null, Instant.now(), null, null, true);
    }

    public static MetricsSnapshot model(InvocationContext context, UUID id, Instant startedAt, String requested,
            ChatResponse response, boolean failed) {
        if (!recorded(context)) return null;
        var base = start(context.invocationParameters());
        Instant end = response != null || failed ? Instant.now() : null;
        var usage = usage(response == null ? null : response.tokenUsage());
        var call = MetricsSnapshot.ModelCall.builder().modelCallId(id).runId(base.runId())
                .invocationId(context.invocationId()).providerId(base.providerId()).requestedModel(text(requested))
                .status(end == null ? "RUNNING" : failed ? "FAILED" : "SUCCEEDED").startedAt(startedAt)
                .endedAt(end).durationMs(end == null ? null : elapsed(startedAt, end)).usageSource("UNAVAILABLE")
                .errorCode(failed ? "MODEL_CALL_FAILED" : null);
        if (response != null) {
            call.responseModel(text(response.modelName())).providerResponseId(text(response.id()))
                    .finishReason(response.finishReason() == null ? null : response.finishReason().name());
            if (usage != null) call.inputTokens(usage.inputTokens()).outputTokens(usage.outputTokens()).totalTokens(usage.totalTokens())
                    .usageSource(response.tokenUsage().totalTokenCount() == null && usage.totalTokens() != null ? "DERIVED_TOTAL" : "PROVIDER")
                    .cachedInputTokens(first(extension(response.tokenUsage(), "cachedInputTokenCount"),
                            extension(response.tokenUsage(), "cacheReadInputTokens"), extension(response.tokenUsage(), "cachedContentTokenCount"),
                            nestedExtension(response.tokenUsage(), "inputTokensDetails", "cachedTokens")))
                    .cacheWriteTokens(extension(response.tokenUsage(), "cacheCreationInputTokens"))
                    .reasoningTokens(first(extension(response.tokenUsage(), "thoughtsTokenCount"),
                            nestedExtension(response.tokenUsage(), "outputTokensDetails", "reasoningTokens")));
        }
        long requests = response == null || response.aiMessage() == null ? 0 : response.aiMessage().toolExecutionRequests().size();
        return base.toBuilder().modelCalls(List.of(call.toolRequestedCount(requests).build())).toolRequestedCount(requests).build();
    }

    public static MetricsSnapshot firstResponse(InvocationParameters parameters, Instant at) {
        var base = start(parameters);
        return base.toBuilder().firstResponseMs(elapsed(base.startedAt(), at)).build();
    }

    public static MetricsSnapshot capability(InvocationContext context, UUID id, Instant startedAt,
            String toolCallId, String name, String kind, String serverId, String version, Boolean failed) {
        if (!recorded(context)) return null;
        var base = start(context.invocationParameters());
        Instant end = failed == null ? null : Instant.now();
        var call = MetricsSnapshot.CapabilityCall.builder().capabilityCallId(id).runId(base.runId())
                .invocationId(context.invocationId()).providerToolCallId(text(toolCallId)).kind(kind)
                .capabilityId(text(name)).capabilityName(text(name)).capabilityVersion(text(version)).serverId(serverId)
                .status(failed == null ? "RUNNING" : failed ? "FAILED" : "SUCCEEDED").startedAt(startedAt).endedAt(end)
                .durationMs(end == null ? null : elapsed(startedAt, end)).errorCode(Boolean.TRUE.equals(failed) ? "CAPABILITY_CALL_FAILED" : null).build();
        return base.toBuilder().capabilityCalls(List.of(call)).build();
    }

    public static MetricsSnapshot capabilityFinished(MetricsSnapshot started, boolean failed) {
        if (started == null) return null;
        var end = Instant.now();
        var call = started.capabilityCalls().getFirst();
        return started.toBuilder().lastObservedAt(end).capabilityCalls(List.of(call.toBuilder()
                .status(failed ? "FAILED" : "SUCCEEDED").endedAt(end).durationMs(elapsed(call.startedAt(), end))
                .errorCode(failed ? "CAPABILITY_CALL_FAILED" : null).build())).build();
    }

    /** Storage merges entries by their stable IDs, so batching, retries and late callbacks do not add counts twice. */
    public static MetricsSnapshot merge(MetricsSnapshot previous, MetricsSnapshot entry) {
        if (previous != null && previous.terminal() && entry.terminal() && !previous.status().equals(entry.status())) return previous;
        if (previous == null) previous = entry.toBuilder().modelCalls(List.of()).capabilityCalls(List.of())
                .toolRequestedCount(0).revision(0).build();
        var modelEntries = mergeModelCalls(previous.modelCalls(), entry.modelCalls());
        var models = modelEntries.stream().filter(call -> call.modelCallId() != null).toList();
        var capabilities = new LinkedHashMap<UUID, MetricsSnapshot.CapabilityCall>();
        previous.capabilityCalls().forEach(call -> capabilities.put(call.capabilityCallId(), call));
        for (var call : entry.capabilityCalls()) {
            var old = capabilities.get(call.capabilityCallId());
            if (old != null && old.endedAt() != null) continue;
            UUID parent = call.modelCallId();
            if (parent == null) parent = models.stream().filter(model -> !model.startedAt().isAfter(call.startedAt()))
                    .max(java.util.Comparator.comparing(MetricsSnapshot.ModelCall::startedAt))
                    .map(MetricsSnapshot.ModelCall::modelCallId).orElse(null);
            capabilities.put(call.capabilityCallId(), call.toBuilder().modelCallId(old == null ? parent : old.modelCallId()).build());
        }
        var terminal = previous.terminal() ? previous : entry;
        String unresolved = terminal.terminal() ? "CANCELLED".equals(terminal.status()) ? "CANCELLED" : "UNKNOWN_OUTCOME" : "RUNNING";
        var modelList = models.stream().map(call -> call.endedAt() != null ? call : call.toBuilder().status(unresolved).build()).toList();
        var capabilityList = capabilities.values().stream().map(call -> call.endedAt() != null ? call : call.toBuilder().status(unresolved).build()).toList();
        long knownIn = modelList.stream().filter(call -> call.inputTokens() != null).count();
        long knownOut = modelList.stream().filter(call -> call.outputTokens() != null).count();
        long knownTotal = modelList.stream().filter(call -> call.totalTokens() != null).count();
        long succeeded = modelList.stream().filter(call -> "SUCCEEDED".equals(call.status())).count();
        long failed = modelList.stream().filter(call -> "FAILED".equals(call.status())).count();
        Long firstResponse = previous.firstResponseMs();
        if (firstResponse == null) firstResponse = entry.firstResponseMs();
        else if (entry.firstResponseMs() != null) firstResponse = Math.min(firstResponse, entry.firstResponseMs());
        String coverage = modelList.isEmpty() ? "NOT_APPLICABLE" : knownIn == modelList.size() && knownOut == modelList.size()
                && knownTotal == modelList.size() ? "COMPLETE" : knownIn + knownOut + knownTotal == 0 ? "UNKNOWN" : "PARTIAL";
        return previous.toBuilder().status(terminal.status()).endedAt(terminal.endedAt()).durationMs(terminal.durationMs())
                .terminalReason(terminal.terminalReason()).errorCode(terminal.errorCode()).firstResponseMs(firstResponse)
                .aggregateUsageReported(previous.aggregateUsageReported() == null ? entry.aggregateUsageReported() : previous.aggregateUsageReported())
                .lastObservedAt(entry.lastObservedAt().isAfter(previous.lastObservedAt()) ? entry.lastObservedAt() : previous.lastObservedAt())
                .revision(Math.max(previous.revision(), entry.revision()) + 1)
                .modelCalls(java.util.stream.Stream.concat(modelList.stream(), modelEntries.stream().filter(call -> call.modelCallId() == null)).toList()).capabilityCalls(capabilityList)
                .modelCallCount(modelList.size()).modelSucceededCount(succeeded).modelFailedCount(failed)
                .modelUnresolvedCount(modelList.size() - succeeded - failed).toolRequestedCount(modelList.stream().mapToLong(MetricsSnapshot.ModelCall::toolRequestedCount).sum())
                .functionCallCount(kind(capabilityList, "FUNCTION")).mcpToolCallCount(kind(capabilityList, "MCP_TOOL"))
                .skillLoadCount(capabilityList.stream().filter(call -> "SKILL_LOAD".equals(call.kind()) && "SUCCEEDED".equals(call.status())).count())
                .skillExecutionCount(kind(capabilityList, "SKILL_EXECUTE"))
                .capabilityFailedCount(capabilityList.stream().filter(call -> "FAILED".equals(call.status())).count())
                .capabilityUnresolvedCount(capabilityList.stream().filter(call -> call.endedAt() == null).count())
                .inputKnownCallCount(knownIn).outputKnownCallCount(knownOut).usageKnownCallCount(knownTotal).usageCoverage(coverage)
                .knownInputTokens(modelList.isEmpty() ? Long.valueOf(0) : knownIn == 0 ? null : Long.valueOf(modelList.stream().filter(call -> call.inputTokens() != null).mapToLong(MetricsSnapshot.ModelCall::inputTokens).sum()))
                .knownOutputTokens(modelList.isEmpty() ? Long.valueOf(0) : knownOut == 0 ? null : Long.valueOf(modelList.stream().filter(call -> call.outputTokens() != null).mapToLong(MetricsSnapshot.ModelCall::outputTokens).sum()))
                .knownTotalTokens(modelList.isEmpty() ? Long.valueOf(0) : knownTotal == 0 ? null : Long.valueOf(modelList.stream().filter(call -> call.totalTokens() != null).mapToLong(MetricsSnapshot.ModelCall::totalTokens).sum())).build();
    }

    /** Completion events carry no client binding; storage pairs them with this invocation's latest request. */
    private static List<MetricsSnapshot.ModelCall> mergeModelCalls(
            List<MetricsSnapshot.ModelCall> previous, List<MetricsSnapshot.ModelCall> entries) {
        var models = new LinkedHashMap<UUID, MetricsSnapshot.ModelCall>();
        var completions = new ArrayList<MetricsSnapshot.ModelCall>();
        for (var call : java.util.stream.Stream.concat(previous.stream(), entries.stream()).toList()) {
            if (call.modelCallId() == null) {
                completions.add(call);
                continue;
            }
            var old = models.get(call.modelCallId());
            if (old != null && old.endedAt() != null) continue;
            models.put(call.modelCallId(), call.toBuilder().sequence(old == null ? models.size() + 1 : old.sequence()).build());
        }
        var pending = new ArrayList<MetricsSnapshot.ModelCall>();
        completions.sort(Comparator.comparing(MetricsSnapshot.ModelCall::endedAt));
        for (var completion : completions) {
            var call = models.values().stream()
                    .filter(value -> Objects.equals(value.invocationId(), completion.invocationId()))
                    .filter(value -> !value.startedAt().isAfter(completion.endedAt()))
                    .max(Comparator.comparing(MetricsSnapshot.ModelCall::startedAt)).orElse(null);
            if (call == null) {
                // A buffer flush may have already sent the request to storage.
                pending.add(completion);
            } else if (call.endedAt() == null) {
                models.put(call.modelCallId(), completion.toBuilder().modelCallId(call.modelCallId())
                        .sequence(call.sequence()).requestedModel(call.requestedModel()).startedAt(call.startedAt())
                        .durationMs(elapsed(call.startedAt(), completion.endedAt())).build());
            }
        }
        return java.util.stream.Stream.concat(models.values().stream(), pending.stream()).toList();
    }

    public static boolean expired(MetricsSnapshot previous, MetricsSnapshot entry, java.time.Duration retention) {
        return previous != null && previous.endedAt() != null && !entry.terminal()
                && entry.lastObservedAt().isAfter(previous.endedAt().plus(retention));
    }

    /** Storage retains IDs for idempotence; read views bound the detailed payload independently of totals. */
    public static MetricsSnapshot details(MetricsSnapshot snapshot, int limit) {
        snapshot = snapshot.toBuilder().modelCalls(snapshot.modelCalls().stream()
                .filter(call -> call.modelCallId() != null).toList()).build();
        int models = Math.min(limit, snapshot.modelCalls().size());
        int capabilities = Math.min(Math.max(0, limit - models), snapshot.capabilityCalls().size());
        long dropped = snapshot.modelCalls().size() + snapshot.capabilityCalls().size() - models - capabilities;
        return dropped == 0 ? snapshot : snapshot.toBuilder().modelCalls(snapshot.modelCalls().subList(0, models))
                .capabilityCalls(snapshot.capabilityCalls().subList(0, capabilities)).detailsTruncated(true).droppedDetailCount(dropped).build();
    }

    private static long kind(List<MetricsSnapshot.CapabilityCall> calls, String kind) { return calls.stream().filter(call -> kind.equals(call.kind())).count(); }
    private static boolean recorded(InvocationContext context) { return context != null && context.invocationParameters() != null && context.invocationParameters().get(LlmClient.RUN_ID) != null; }
    private static long elapsed(Instant start, Instant end) { return Math.max(0, Duration.between(start, end).toMillis()); }
    private static Long count(Integer value) { return value == null || value < 0 ? null : value.longValue(); }
    private static MetricsSnapshot.Usage usage(TokenUsage value) {
        if (value == null) return null;
        Long in = count(value.inputTokenCount()), out = count(value.outputTokenCount()), total = count(value.totalTokenCount());
        return new MetricsSnapshot.Usage(in, out, total != null ? total : in != null && out != null ? in + out : null);
    }
    private static String text(String value) { return value == null ? null : value.substring(0, Math.min(255, value.length())); }
    private static Long first(Long... values) { for (Long value : values) if (value != null) return value; return null; }
    private static Long extension(Object value, String getter) {
        try { var result = value.getClass().getMethod(getter).invoke(value); return result instanceof Number n && n.longValue() >= 0 ? n.longValue() : null; }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }
    private static Long nestedExtension(Object value, String outer, String getter) {
        try { Object nested = value.getClass().getMethod(outer).invoke(value); return nested == null ? null : extension(nested, getter); }
        catch (ReflectiveOperationException | RuntimeException ignored) { return null; }
    }
}
