package com.mindrails.backend.agent.registry;

import static com.mindrails.backend.persistence.RegistryJson.*;

import com.mindrails.backend.agent.model.Agent;
import com.mindrails.backend.persistence.JdbcRegistry;
import com.mindrails.backend.persistence.RegistryJson;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;

import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.stream.Collectors;

public final class MysqlAgentRegistry extends JdbcRegistry<Agent> implements AgentRegistry {
    private final RegistryJson json;
    private final SessionRegistry sessions;
    private TaskRegistry tasks;

    public MysqlAgentRegistry(JdbcTemplate jdbc, ObjectMapper mapper, SessionRegistry sessions, TaskRegistry tasks) {
        this(jdbc, mapper, sessions); this.tasks = tasks;
    }

    public MysqlAgentRegistry(JdbcTemplate jdbc, ObjectMapper mapper, SessionRegistry sessions) {
        super(jdbc, "agents", "id,owner_id,name,description,goal,system_message,skill_ids,connection_ids,memory_id,knowledge_base_ids,created_at,updated_at,version",
                Agent::getId, Agent::getVersion, Agent::setVersion);
        json = new RegistryJson(mapper);
        this.sessions = sessions;
    }

    @Override protected String visibilityPredicate() {
        // Preserve historical SmartApp agents without exposing them as ordinary agents.
        return "special_key IS NULL";
    }

    @Override public Optional<Agent> mutate(UUID id, Consumer<Agent> mutation) {
        return update(id, mutation);
    }

    @Override protected Agent read(ResultSet rs) throws SQLException {
        return Agent.builder().id(uuid(rs.getString("id"))).ownerId(uuid(rs.getString("owner_id")))
                .name(rs.getString("name")).description(rs.getString("description")).goal(rs.getString("goal"))
                .systemMessage(rs.getString("system_message")).skillIds(json.ids(rs.getString("skill_ids")))
                .connectionIds(json.ids(rs.getString("connection_ids"))).memoryId(uuid(rs.getString("memory_id")))
                .knowledgeBaseIds(json.ids(rs.getString("knowledge_base_ids")))
                .createdAt(rs.getTimestamp("created_at").toInstant()).updatedAt(rs.getTimestamp("updated_at").toInstant())
                .version(rs.getLong("version")).build();
    }

    @Override protected Agent enrich(Agent agent) {
        agent.setSessions(sessions.listAllManaged(agent.getId()));
        if (tasks != null) {
            var all = new java.util.ArrayList<com.mindrails.backend.agent.model.Task>();
            for (int page=0;;page++) { var batch=tasks.list(agent.getId(),page,100); all.addAll(batch); if(batch.size()<100) break; }
            agent.setTasks(all);
        }
        return agent;
    }

    @Override protected MapSqlParameterSource parameters(Agent agent) {
        return new MapSqlParameterSource().addValue("id", key(agent.getId())).addValue("owner_id", key(agent.getOwnerId()))
                .addValue("name", agent.getName()).addValue("description", agent.getDescription()).addValue("goal", agent.getGoal())
                .addValue("system_message", agent.getSystemMessage()).addValue("skill_ids", json.ids(agent.getSkillIds()))
                .addValue("connection_ids", json.ids(agent.getConnectionIds())).addValue("memory_id", key(agent.getMemoryId()))
                .addValue("knowledge_base_ids", json.ids(agent.getKnowledgeBaseIds()))
                .addValue("created_at", Timestamp.from(agent.getCreatedAt())).addValue("updated_at", Timestamp.from(agent.getUpdatedAt()))
                .addValue("version", agent.getVersion());
    }

    @Override public List<Agent> listManaged(int page, int size) { return list("", new MapSqlParameterSource(), page, size); }

    @Override public Optional<AgentView> find(UUID id) {
        var agent = findManaged(id).orElse(null);
        if (agent == null) return Optional.empty();
        var ids = agent.getSessions().stream().map(session -> session.getId()).collect(Collectors.toSet());
        java.util.Set<UUID> taskIds = tasks == null ? java.util.Set.of() : jdbc.query(
                "SELECT id FROM tasks WHERE parent_agent_id=:id",new MapSqlParameterSource("id",key(id)),
                (rs,row) -> uuid(rs.getString(1))).stream().collect(Collectors.toSet());
        return Optional.of(new AgentView(id, ids, taskIds));
    }

    @Override public boolean derivesTaskMembership() { return tasks != null; }

    @Override public boolean derivesSessionMembership() { return true; }
}
