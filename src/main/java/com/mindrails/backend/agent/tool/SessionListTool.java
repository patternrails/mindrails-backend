package com.mindrails.backend.agent.tool;

import com.mindrails.backend.agent.model.LlmClient;
import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.agent.registry.SessionRegistry;
import com.mindrails.backend.identity.ResourceAccess;
import com.mindrails.backend.tool.IdentifiedTool;
import com.mindrails.backend.tool.ToolFailure;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import dev.langchain4j.invocation.InvocationContext;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Discovers communication candidates using server-scoped ownership, without loading conversations. */
@Component
public final class SessionListTool implements IdentifiedTool {
    public static final String NAME = "session_list";
    private final SessionRegistry sessions;
    private final AgentRegistry agents;
    public SessionListTool(SessionRegistry sessions, AgentRegistry agents) { this.sessions = sessions; this.agents = agents; }
    @Override public UUID id() { return UUID.fromString("9e08b188-7e09-4623-b96c-1f092a575adb"); }

    @Tool(name = NAME, value = "Find the current user's other ordinary Sessions across Agents before using session_communicate. "
            + "Returns exact IDs, Session names, Agent names and update times; excludes this Session. "
            + "Use a short title keyword such as Rust, or omit query to browse. This searches Session titles, not conversation text. "
            + "Follow nextOffset if more results are needed. Select the clearly matching Session; if several plausible candidates remain, "
            + "ask the user to choose using the returned names. Do not guess an ID or ask the user to find a UUID you can discover here.")
    public Result list(
            @P(name = "query", value = "Optional case-insensitive substring in Session title; omit to browse.", required = false) String query,
            @P(name = "offset", value = "Start at zero; use nextOffset from a previous result.", required = false) Integer offset,
            @P(name = "limit", value = "Number of results, 1..50; default 20.", required = false) Integer limit,
            InvocationContext context) {
        LlmClient.checkCancelled(context);
        var parameters = context == null ? null : context.invocationParameters();
        if (parameters == null || parameters.get(LlmClient.OWNER_ID) == null || parameters.get(LlmClient.SESSION_ID) == null
                || parameters.get(LlmClient.AGENT_ID) == null || parameters.containsKey(LlmClient.TASK_ID)) {
            throw new ToolFailure("session_list requires an ordinary Session invocation");
        }
        int start = offset == null ? 0 : offset, count = limit == null ? 20 : limit;
        String needle = query == null ? "" : query.strip();
        if (start < 0 || count < 1 || count > 50 || needle.length() > 200) throw new ToolFailure("Invalid offset, limit or query");
        UUID owner = parameters.get(LlmClient.OWNER_ID), sourceId = parameters.get(LlmClient.SESSION_ID);
        try (var ignored = ResourceAccess.as(owner)) {
            var source = sessions.findManaged(sourceId).orElseThrow(() -> new ToolFailure("Session not found"));
            if (!ResourceAccess.sameOwner(owner, source.getOwnerId())
                    || !source.getParentAgentId().equals(parameters.get(LlmClient.AGENT_ID))) throw new ToolFailure("Session not found");
            var found = sessions.discover(owner, sourceId, needle, start, count + 1);
            var candidates = found.stream().limit(count).map(s -> new Entry(s.sessionId(), s.name(), s.agentId(),
                    agents.findManaged(s.agentId()).map(com.mindrails.backend.agent.model.Agent::getName).orElse(null), s.updatedAt())).toList();
            return new Result(sourceId, candidates, found.size() > count ? start + count : null);
        }
    }
    public record Entry(UUID sessionId, String name, UUID agentId, String agentName, Instant updatedAt) {}
    public record Result(UUID currentSessionId, List<Entry> sessions, Integer nextOffset) {}
}
