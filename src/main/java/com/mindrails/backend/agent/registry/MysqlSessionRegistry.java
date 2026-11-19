package com.mindrails.backend.agent.registry;

import static com.mindrails.backend.persistence.RegistryJson.*;

import com.mindrails.backend.agent.model.Session;
import com.mindrails.backend.persistence.JdbcRegistry;
import com.mindrails.backend.persistence.RegistryJson;
import com.mindrails.backend.provider.ProviderRegistry;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

public final class MysqlSessionRegistry extends JdbcRegistry<Session> implements SessionRegistry {
    private static final String VISIBLE_SESSIONS = "smart_app IS NULL AND NOT EXISTS "
            + "(SELECT 1 FROM agents WHERE agents.id=sessions.parent_agent_id AND special_key IS NOT NULL)";

    private final RegistryJson json;
    private final ProviderRegistry providers;

    public MysqlSessionRegistry(
            JdbcTemplate jdbc, ObjectMapper mapper, ProviderRegistry providers) {
        super(
                jdbc,
                "sessions",
                "id,owner_id,parent_agent_id,name,provider_id,system_message,skill_ids,memory_id,created_at,updated_at,version",
                Session::getId,
                Session::getVersion,
                Session::setVersion);
        json = new RegistryJson(mapper);
        this.providers = providers;
        // The Provider catalog must resolve every visible Session before serving traffic.
        for (String id :
                jdbc.queryForList("SELECT DISTINCT provider_id FROM sessions WHERE " + VISIBLE_SESSIONS, String.class)) {
            if (providers.find(UUID.fromString(id)).isEmpty())
                throw new IllegalStateException("Session references missing Provider " + id);
        }
    }

    @Override protected String visibilityPredicate() {
        return VISIBLE_SESSIONS;
    }

    @Override protected UUID inheritedOwner(Session entity) {
        if (entity.getParentAgentId() == null) return null;
        return jdbc.query("SELECT owner_id FROM agents WHERE id=:id",
                new MapSqlParameterSource("id", entity.getParentAgentId().toString()),
                (rs, row) -> UUID.fromString(rs.getString(1))).stream().findFirst().orElse(null);
    }

    @Override
    public Optional<Session> save(Session session) {
        return saveSnapshot(session);
    }

    @Override
    protected void validateSnapshot(Session stored, Session update) {
        if (!Objects.equals(stored.getMemoryId(), update.getMemoryId())
                || !Objects.equals(stored.getParentAgentId(), update.getParentAgentId())) {
            throw new IllegalArgumentException("Session memory and owner are immutable");
        }
    }

    @Override
    protected Session read(ResultSet rs) throws SQLException {
        UUID providerId = uuid(rs.getString("provider_id"));
        return Session.builder()
                .id(uuid(rs.getString("id")))
                .parentAgentId(uuid(rs.getString("parent_agent_id"))).ownerId(uuid(rs.getString("owner_id")))
                .provider(
                        providers
                                .find(providerId)
                                .orElseThrow(
                                        () ->
                                                new IllegalStateException(
                                                        "Missing Provider " + providerId)))
                .name(rs.getString("name"))
                .systemMessage(rs.getString("system_message"))
                .skillIds(json.ids(rs.getString("skill_ids")))
                .memoryId(uuid(rs.getString("memory_id")))
                .createdAt(rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at").toInstant())
                .version(rs.getLong("version"))
                .build();
    }

    @Override
    protected MapSqlParameterSource parameters(Session session) {
        return new MapSqlParameterSource()
                .addValue("id", key(session.getId())).addValue("owner_id", key(session.getOwnerId()))
                .addValue("parent_agent_id", key(session.getParentAgentId()))
                .addValue("provider_id", key(session.getProvider().getId()))
                .addValue("name", session.getName())
                .addValue("system_message", session.getSystemMessage())
                .addValue("skill_ids", json.ids(session.getSkillIds()))
                .addValue("memory_id", key(session.getMemoryId()))
                .addValue("created_at", Timestamp.from(session.getCreatedAt()))
                .addValue("updated_at", Timestamp.from(session.getUpdatedAt()))
                .addValue("version", session.getVersion());
    }

    @Override
    public List<Session> listManaged(UUID agentId, int page, int size) {
        return list(
                "WHERE parent_agent_id = :owner",
                new MapSqlParameterSource("owner", key(agentId)),
                page,
                size);
    }

    @Override
    public List<Candidate> discover(UUID ownerId, UUID excludeSessionId, String query, int offset, int limit) {
        if (!com.mindrails.backend.identity.ResourceAccess.visible(ownerId)) return List.of();
        return jdbc.query("SELECT id,parent_agent_id,name,updated_at FROM sessions WHERE owner_id=:owner"
                        + " AND id<>:exclude AND " + VISIBLE_SESSIONS
                        + " AND LOCATE(:query,LOWER(COALESCE(name,'')))>0"
                        + " ORDER BY updated_at DESC,id LIMIT :limit OFFSET :offset",
                new MapSqlParameterSource("owner", key(ownerId)).addValue("exclude", key(excludeSessionId))
                        .addValue("query", query.toLowerCase(java.util.Locale.ROOT)).addValue("offset", offset).addValue("limit", limit),
                (rs, row) -> new Candidate(uuid(rs.getString("id")), uuid(rs.getString("parent_agent_id")),
                        rs.getString("name"), rs.getTimestamp("updated_at").toInstant()));
    }

    @Override
    public Optional<SessionView> find(UUID id) {
        return findManaged(id)
                .map(
                        session ->
                                new SessionView(
                                        session.getId(),
                                        session.getParentAgentId(),
                                        session.getProvider().getId(),
                                        session.getProvider().getStreaming()));
    }
}
