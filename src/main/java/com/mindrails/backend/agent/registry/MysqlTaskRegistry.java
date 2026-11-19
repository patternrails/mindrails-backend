package com.mindrails.backend.agent.registry;

import static com.mindrails.backend.persistence.RegistryJson.*;
import com.mindrails.backend.agent.model.Task;
import com.mindrails.backend.persistence.*;
import com.mindrails.backend.provider.ProviderRegistry;
import java.sql.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import tools.jackson.databind.ObjectMapper;

public final class MysqlTaskRegistry extends JdbcRegistry<Task> implements TaskRegistry {
    private final RegistryJson json;
    private final ProviderRegistry providers;
    public MysqlTaskRegistry(JdbcTemplate jdbc, ObjectMapper mapper, ProviderRegistry providers) {
        super(jdbc, "tasks", "id,owner_id,parent_agent_id,name,provider_id,instructions,skill_ids,cronjob,created_at,updated_at,version",
                Task::getId, Task::getVersion, Task::setVersion);
        json = new RegistryJson(mapper); this.providers = providers;
    }
    @Override protected UUID inheritedOwner(Task entity) {
        if (entity.getParentAgentId() == null) return null;
        return jdbc.query("SELECT owner_id FROM agents WHERE id=:id",
                new MapSqlParameterSource("id", entity.getParentAgentId().toString()),
                (rs, row) -> UUID.fromString(rs.getString(1))).stream().findFirst().orElse(null);
    }

    public Optional<Task> find(UUID id) { return findManaged(id); }
    public Optional<Task> save(Task task) { return saveSnapshot(task); }
    protected void validateSnapshot(Task stored, Task update) {
        if (!Objects.equals(stored.getParentAgentId(), update.getParentAgentId())) throw new IllegalArgumentException("Task owner is immutable");
    }
    protected Task read(ResultSet rs) throws SQLException {
        return Task.builder().id(uuid(rs.getString("id"))).parentAgentId(uuid(rs.getString("parent_agent_id"))).ownerId(uuid(rs.getString("owner_id")))
                .name(rs.getString("name")).provider(providers.find(uuid(rs.getString("provider_id")))
                        .orElseThrow(() -> new IllegalStateException("Task provider no longer exists")))
                .instructions(rs.getString("instructions")).skillIds(json.ids(rs.getString("skill_ids")))
                .cronjob(rs.getString("cronjob")).createdAt(rs.getTimestamp("created_at").toInstant())
                .updatedAt(rs.getTimestamp("updated_at").toInstant()).version(rs.getLong("version")).build();
    }
    protected MapSqlParameterSource parameters(Task t) {
        return new MapSqlParameterSource().addValue("id", key(t.getId())).addValue("owner_id", key(t.getOwnerId())).addValue("parent_agent_id", key(t.getParentAgentId()))
                .addValue("name", t.getName()).addValue("provider_id", key(t.getProvider().getId()))
                .addValue("instructions", t.getInstructions()).addValue("skill_ids", json.ids(t.getSkillIds()))
                .addValue("cronjob", t.getCronjob()).addValue("created_at", Timestamp.from(t.getCreatedAt()))
                .addValue("updated_at", Timestamp.from(t.getUpdatedAt())).addValue("version", t.getVersion());
    }
    public List<Task> list(UUID agent, int page, int size) {
        return list("WHERE parent_agent_id=:agent", new MapSqlParameterSource("agent", key(agent)), page, size);
    }
    public boolean referencesProvider(UUID id) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM tasks WHERE provider_id=:id",new MapSqlParameterSource("id",key(id)),Integer.class)>0;
    }
    public List<Task> scheduled() {
        return jdbc.query("SELECT * FROM tasks WHERE cronjob IS NOT NULL", (rs, n) -> read(rs));
    }
}
