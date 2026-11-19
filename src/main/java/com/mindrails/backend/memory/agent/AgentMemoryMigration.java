package com.mindrails.backend.memory.agent;

import static com.mindrails.backend.persistence.RegistryJson.uuid;

import com.mindrails.backend.agent.registry.AgentRegistry;
import java.util.UUID;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.net.URI;
import java.sql.ResultSet;
import java.sql.SQLException;
import com.mindrails.backend.embedding.JsonHttpClient;
import com.mindrails.backend.embedding.QdrantProperties;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/** Explicit offline import/reindex command. Never overwrites conflicting target entries. */
@Component
@ConditionalOnProperty(prefix = "mindrails.agent-memory.migrate", name = "enabled", havingValue = "true")
public final class AgentMemoryMigration implements ApplicationRunner {
    private final AgentMemoryStore target;
    private final AgentMemoryProperties properties;
    private final QdrantProperties qdrant;
    private final ObjectProvider<JdbcTemplate> jdbc;
    private final ObjectMapper mapper;
    private final AgentRegistry agents;
    private final Environment environment;
    private final ConfigurableApplicationContext context;

    public AgentMemoryMigration(AgentMemoryStore target, AgentMemoryProperties properties,
            @Qualifier("mindrails.qdrant-com.mindrails.backend.embedding.QdrantProperties") QdrantProperties qdrant,
            @Qualifier("persistenceJdbcTemplate") ObjectProvider<JdbcTemplate> jdbc, ObjectMapper mapper,
            AgentRegistry agents, Environment environment, ConfigurableApplicationContext context) {
        this.target = target; this.properties = properties; this.qdrant = qdrant; this.jdbc = jdbc; this.mapper = mapper;
        this.agents = agents; this.environment = environment; this.context = context;
    }

    @Override public void run(ApplicationArguments arguments) {
        if (!(target instanceof QdrantAgentMemoryStore) || !target.available())
            throw new IllegalStateException("Memory migration requires Qdrant Agent memory with a default embedding provider");
        if (!"none".equalsIgnoreCase(environment.getProperty("spring.main.web-application-type", "")))
            throw new IllegalStateException("Run memory migration offline with spring.main.web-application-type=none");
        String selected = environment.getProperty("mindrails.agent-memory.migrate.agent-id", "");
        UUID agentId = selected.isBlank() ? null : UUID.fromString(selected);
        var grouped = readSource(agentId).stream().collect(java.util.stream.Collectors.groupingBy(AgentMemoryEntry::memoryId));
        int imported = 0, unchanged = 0;
        for (var group : grouped.entrySet()) {
            var memoryId = group.getKey();
            var entries = group.getValue();
            var merged = new java.util.LinkedHashMap<UUID, AgentMemoryEntry>();
            target.entries(memoryId).forEach(entry -> merged.put(entry.id(), entry));
            entries.forEach(entry -> merged.putIfAbsent(entry.id(), entry));
            var retrieval = new AgentMemoryRetrieval(properties);
            if (merged.size() > properties.getMaxEntries()
                    || merged.values().stream().anyMatch(entry -> entry.content().length() > properties.getMaxEntryChars())
                    || merged.values().stream().filter(AgentMemoryEntry::pinned).count() > properties.getRetrieval().getMaxResults()
                    || retrieval.pinnedChars(java.util.List.copyOf(merged.values())) > properties.getRetrieval().getMaxContextChars())
                throw new IllegalStateException("Migrated memory exceeds configured limits: " + memoryId);
            for (var entry : entries) {
                var owner = agents.findManaged(entry.agentId()).orElseThrow(() -> new IllegalStateException("Migration Agent is unavailable"));
                if (!owner.getMemoryId().equals(entry.memoryId())) throw new IllegalStateException("Migration requires the original persistent Agent identities");
                var existing = target.find(memoryId, entry.id()).orElse(null);
                if (existing != null) {
                    if (!existing.equals(entry)) throw new IllegalStateException("Migration target has a conflicting entry: " + entry.id());
                    unchanged++; continue;
                }
                target.insert(entry); imported++;
            }
        }
        System.out.println("Agent memory migration completed: imported=" + imported + ", unchanged=" + unchanged);
        context.close();
    }
    private List<AgentMemoryEntry> readSource(UUID agentId) {
        String mode = environment.getProperty("mindrails.agent-memory.migrate.source", "qdrant");
        if ("mysql".equalsIgnoreCase(mode)) return readLegacyMysql(agentId);
        if (!"qdrant".equalsIgnoreCase(mode))
            throw new IllegalArgumentException("Migration source must be qdrant or legacy mysql");

        String collection = environment.getProperty("mindrails.agent-memory.migrate.source-collection", "");
        if (!collection.matches("[A-Za-z0-9_-]{1,100}") || collection.equals(properties.getQdrant().getCollection()))
            throw new IllegalArgumentException("Qdrant rebuild requires a distinct source-collection");
        var settings = qdrant;
        URI uri;
        try { uri = new URI(settings.isTls() ? "https" : "http", null, settings.getHost(), settings.getPort(),
                "/collections/" + collection + "/points/scroll", null, null); }
        catch (java.net.URISyntaxException invalid) { throw new IllegalArgumentException("Invalid Qdrant address"); }
        var entries = new ArrayList<AgentMemoryEntry>();
        var body = new java.util.LinkedHashMap<String, Object>(Map.of("limit", 256, "with_payload", true, "with_vector", false));
        if (agentId != null) body.put("filter", Map.of("must", List.of(Map.of("key", "agent_id", "match", Map.of("value", agentId.toString())))));
        try (var http = new JsonHttpClient(mapper, settings.getTimeoutMillis())) {
            while (true) {
                var result = http.request("POST", uri, body, settings.getApiKey().isBlank() ? Map.of() : Map.of("api-key", settings.getApiKey()))
                        .requireSuccess().path("result");
                if (!result.path("points").isArray()) throw new IllegalStateException("Invalid Qdrant source response");
                for (var point : result.path("points")) {
                    var entry = mapper.treeToValue(point.path("payload").path("entry"), AgentMemoryEntry.class);
                    if (entry == null || !entry.id().toString().equals(point.path("id").asText())
                            || (agentId != null && !agentId.equals(entry.agentId())))
                        throw new IllegalStateException("Invalid Qdrant migration scope");
                    entries.add(entry);
                }
                var offset = result.path("next_page_offset");
                if (offset.isMissingNode() || offset.isNull()) break;
                body.put("offset", offset);
            }
        }
        return List.copyOf(entries);
    }

    /** Read-only import of records created by older releases. */
    private List<AgentMemoryEntry> readLegacyMysql(UUID agentId) {
        var sql = new JdbcTemplate(jdbc.getObject().getDataSource());
        sql.setQueryTimeout(5);
        String select = "SELECT * FROM agent_memory_entries";
        String order = " ORDER BY memory_id, created_at, id";
        return agentId == null
                ? sql.query(select + order, this::legacyRow)
                : sql.query(select + " WHERE agent_id = ?" + order, this::legacyRow, agentId.toString());
    }

    private AgentMemoryEntry legacyRow(ResultSet rs, int ignored) throws SQLException {
        return new AgentMemoryEntry(uuid(rs.getString("id")), uuid(rs.getString("agent_id")), uuid(rs.getString("memory_id")),
                rs.getString("entry_key"), AgentMemoryEntry.Type.valueOf(rs.getString("type")),
                rs.getString("content"), mapper.readValue(rs.getString("tags"), new TypeReference<List<String>>() {}),
                rs.getBoolean("pinned"), rs.getInt("priority"), uuid(rs.getString("source_session_id")),
                rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("updated_at").toInstant(), rs.getLong("version"));
    }
}
