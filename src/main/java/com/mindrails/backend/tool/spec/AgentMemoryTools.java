package com.mindrails.backend.tool.spec;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.memory.agent.AgentMemoryEntry;
import com.mindrails.backend.memory.agent.AgentMemoryInput;
import com.mindrails.backend.memory.agent.AgentMemoryService;
import com.mindrails.backend.tool.IdentifiedTool;
import com.mindrails.backend.tool.ToolFailure;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationContext;

import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

import tools.jackson.databind.json.JsonMapper;

/** Shared stateless tool; identity comes from server-supplied invocation IDs. */
public final class AgentMemoryTools implements IdentifiedTool {
    public static final UUID ID = UUID.fromString("83ced006-3d17-4f41-a7db-d9d51f948c77");
    private static final Set<String> NAMES = Set.of("agent_memory_create", "agent_memory_get", "agent_memory_search",
            "agent_memory_update", "agent_memory_delete");
    private static final JsonMapper JSON = JsonMapper.builder().build();
    public static final String INSTRUCTIONS = """
            Agent long-term memory tools:
            A budgeted snapshot of this Agent's saved memories is automatically supplied in each request
            as reference context. Use the supplied established preferences, facts and decisions when
            relevant; you do not need agent_memory_get/search to see or use that supplied context.
            Use get/search to recall additional memories outside that snapshot or obtain the latest
            version before updating or deleting. An absent entry in the snapshot does not prove it is absent in storage.
            Proactively save durable, established user preferences, project facts and important decisions
            that will help future sessions. Save when the user asks you to remember, or when a useful fact
            or decision becomes established; no separate client request is necessary.
            Do not store guesses, temporary task progress, whole conversations, or raw tool output.
            Respect the user's instructions about what to remember or not remember.
            Use stable lowercase keys. Read/search before changing an existing fact; updates and deletes
            require the latest version. Delete only when the user asks to forget or remove a memory.
            Say a memory was saved, changed or deleted only after the tool reports success.
            A failed memory operation does not mean it succeeded. If it cannot be repaired, tell the user.
            """;

    private final AgentMemoryService memory;

    public AgentMemoryTools(AgentMemoryService memory) {
        this.memory = Objects.requireNonNull(memory);
    }

    @Override public UUID id() { return ID; }
    public static boolean isMemoryTool(String name) { return NAMES.contains(name); }

    @Tool(name = "agent_memory_create", value = INSTRUCTIONS + "\nRemember a durable, established preference, fact or decision for this Agent's future sessions. "
            + "Use when the user asks to remember or a useful decision becomes established. Do not save guesses or transient progress. "
            + "An identical key/content retry returns UNCHANGED; a different fact at the same key is a conflict. Read then update it. "
            + "Only claim the memory is saved after success.")
    public Result create(
            @P(name = "key", value = "Stable lowercase semantic key, e.g. response.language or project.database; max 128 chars.") String key,
            @P(name = "type", value = "FACT, PREFERENCE or DECISION.", defaultValue = "FACT") AgentMemoryEntry.Type type,
            @P(name = "content", value = "A concise established fact or preference, useful beyond the current task.") String content,
            @P(name = "tags", value = "Optional short keywords for future retrieval.", defaultValue = "[]") List<String> tags,
            InvocationContext context) {
        UUID agentId = identity(context, LlmClient.AGENT_ID);
        UUID sessionId = optionalIdentity(context, LlmClient.SESSION_ID);
        var saved = value(execute(context, () -> memory.createOrGet(agentId,
                new AgentMemoryInput(key, type, content, tags, false, 0, sessionId, null))));
        return result(saved.created() ? "CREATED" : "UNCHANGED", List.of(saved.entry()));
    }

    @Tool(name = "agent_memory_get", value = "Read one Agent memory by its stable key, including its current version. "
            + "Use before updating or forgetting a fact. Cannot access any other Agent.")
    public Result get(@P(name = "key", value = "The exact memory key.") String key, InvocationContext context) {
        UUID agentId = identity(context, LlmClient.AGENT_ID);
        return result("FOUND", List.of(value(execute(context, () -> memory.getByKey(agentId, key)))));
    }

    @Tool(name = "agent_memory_search", value = "Find relevant memories belonging to this Agent using the configured retrieval backend. "
            + "Use a natural-language question or relevant keywords to recall earlier decisions or find an existing key before saving a fact.")
    public Result search(@P(name = "query", value = "A question describing the memory to recall, or relevant keywords, in Chinese or English.") String query, InvocationContext context) {
        UUID agentId = identity(context, LlmClient.AGENT_ID);
        return result("RESULTS", value(execute(context, () -> memory.searchForTool(agentId, query))));
    }

    @Tool(name = "agent_memory_update", value = "Replace an established memory when a fact or user preference changes. "
            + "First get/search its current key and version. Provide the complete replacement content and tags. "
            + "A stale version returns a conflict; reread rather than blindly overwriting. Management pin/priority settings are preserved.")
    public Result update(
            @P(name = "key", value = "Existing memory key.") String key,
            @P(name = "expectedVersion", value = "Current version returned by get/search; required.") Long expectedVersion,
            @P(name = "type", value = "Replacement type: FACT, PREFERENCE or DECISION.") AgentMemoryEntry.Type type,
            @P(name = "content", value = "Complete corrected fact or preference.") String content,
            @P(name = "tags", value = "Complete replacement keyword list.", defaultValue = "[]") List<String> tags,
            InvocationContext context) {
        UUID agentId = identity(context, LlmClient.AGENT_ID);
        UUID sessionId = optionalIdentity(context, LlmClient.SESSION_ID);
        return result("UPDATED", List.of(value(execute(context, () -> memory.updateByKey(agentId, key,
                expectedVersion, type, content, tags, sessionId)))));
    }

    @Tool(name = "agent_memory_delete", value = "Forget a saved Agent memory only when the user asks to forget or remove it. "
            + "Read the current key/version first. Does not delete Session conversations or another Agent's memories.")
    public Result delete(
            @P(name = "key", value = "Exact key of the memory the user asked to forget.") String key,
            @P(name = "expectedVersion", value = "Current version returned by get/search; required.") Long expectedVersion,
            InvocationContext context) {
        UUID agentId = identity(context, LlmClient.AGENT_ID);
        value(execute(context, () -> memory.deleteByKey(agentId, key, expectedVersion)));
        return result("DELETED", List.of());
    }

    private <T> ServiceResult<T> execute(InvocationContext context, Supplier<ServiceResult<T>> action) {
        LlmClient.checkCancelled(context);
        UUID taskId = optionalIdentity(context, LlmClient.TASK_ID);
        return taskId == null ? memory.forToolSession(identity(context, LlmClient.SESSION_ID), identity(context, LlmClient.AGENT_ID), action)
                : memory.forToolTask(taskId, identity(context,LlmClient.AGENT_ID), action);
    }

    private static UUID optionalIdentity(InvocationContext context, String key) {
        LlmClient.checkCancelled(context);
        return context == null || context.invocationParameters() == null ? null : context.invocationParameters().get(key);
    }

    private static UUID identity(InvocationContext context, String key) {
        LlmClient.checkCancelled(context);
        UUID id = context == null || context.invocationParameters() == null ? null : context.invocationParameters().get(key);
        if (id == null) throw new Failure(ErrorCode.INVALID_ARGUMENT, "Server-supplied Session and Agent IDs are required");
        return id;
    }

    private static <T> T value(ServiceResult<T> result) {
        return switch (result) {
            case ServiceResult.Success<T> success -> success.value();
            case ServiceResult.Failure<T> failure -> throw new Failure(failure.code(), failure.message());
        };
    }

    private static Result result(String status, List<AgentMemoryEntry> entries) {
        return new Result(true, status, null, null, entries.stream().map(entry -> new Entry(entry.id(), entry.key(),
                entry.type(), entry.content(), entry.tags(), entry.version(), entry.sourceSessionId())).toList());
    }

    private static String error(ErrorCode code, String message) {
        return JSON.writeValueAsString(new Result(false, "ERROR", code, message, List.of()));
    }

    public record Entry(UUID id, String key, AgentMemoryEntry.Type type, String content, List<String> tags,
            long version, UUID sourceSessionId) {}
    public record Result(boolean success, String status, ErrorCode code, String message, List<Entry> memories) {}
    /** The global tool handler returns this tool-owned business error without changing its payload. */
    private static final class Failure extends ToolFailure {
        private Failure(ErrorCode code, String message) { super(error(code, message)); }
    }
}
