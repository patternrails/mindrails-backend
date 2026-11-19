package com.mindrails.backend.knowledge.registry;

import com.mindrails.backend.knowledge.model.VersionedKnowledgeEntity;
import com.mindrails.backend.knowledge.service.KnowledgeException;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

abstract class JdbcKnowledgeSnapshots {
    protected final JdbcTemplate jdbc;
    protected final ObjectMapper mapper;

    JdbcKnowledgeSnapshots(JdbcTemplate shared, ObjectMapper mapper) {
        this.jdbc = new JdbcTemplate(Objects.requireNonNull(shared.getDataSource()));
        this.jdbc.setQueryTimeout(10);
        this.mapper = mapper;
    }

    protected <T extends VersionedKnowledgeEntity> T one(String table, UUID id, Class<T> type) {
        if (id == null) {
            throw KnowledgeException.missing();
        }
        return query(table, " WHERE id=?", type, id.toString()).stream()
                .findFirst()
                .orElseThrow(KnowledgeException::missing);
    }

    protected <T extends VersionedKnowledgeEntity> List<T> query(
            String table, String suffix, Class<T> type, Object... args) {
        if (table.equals("knowledge_bases") && com.mindrails.backend.identity.ResourceAccess.userId() != null) {
            String condition = " owner_id=?";
            int order = suffix.indexOf(" ORDER BY");
            String tail = order < 0 ? "" : suffix.substring(order);
            String predicate = order < 0 ? suffix : suffix.substring(0, order);
            suffix = predicate + (predicate.contains("WHERE") ? " AND" : " WHERE") + condition + tail;
            var scoped = new ArrayList<>(java.util.Arrays.asList(args));
            scoped.add(com.mindrails.backend.identity.ResourceAccess.userId().toString()); args = scoped.toArray();
        }
        return jdbc.query(
                "SELECT payload,version" + (table.equals("knowledge_bases") ? ",owner_id" : "") + " FROM " + table + suffix,
                (rs, row) -> {
                    T entity = mapper.readValue(rs.getString("payload"), type);
                    entity.setVersion(rs.getLong("version"));
                    if (entity instanceof com.mindrails.backend.knowledge.model.KnowledgeBase base)
                        base.setOwnerId(UUID.fromString(rs.getString("owner_id")));
                    return entity;
                },
                args);
    }

    /** Extra columns are fixed by the concrete registry, never supplied by API callers. */
    protected void insert(
            String table,
            VersionedKnowledgeEntity entity,
            String state,
            Instant createdAt,
            Map<String, Object> extraColumns) {
        var columns =
                new ArrayList<>(
                        List.of("id", "state", "payload", "version", "created_at", "updated_at"));
        var values =
                new ArrayList<Object>(
                        List.of(
                                entity.getId().toString(),
                                state,
                                mapper.writeValueAsString(entity),
                                entity.getVersion(),
                                Timestamp.from(createdAt),
                                Timestamp.from(createdAt)));
        extraColumns.forEach(
                (column, value) -> {
                    columns.add(column);
                    values.add(value);
                });
        String placeholders = String.join(",", Collections.nCopies(values.size(), "?"));
        String sql =
                "INSERT INTO "
                        + table
                        + " ("
                        + String.join(",", columns)
                        + ") VALUES ("
                        + placeholders
                        + ")";
        jdbc.update(sql, values.toArray());
    }

    protected void save(String table, VersionedKnowledgeEntity entity, String state) {
        save(table, entity, state, Map.of());
    }

    protected void save(
            String table,
            VersionedKnowledgeEntity entity,
            String state,
            Map<String, Object> extraColumns) {
        var assignments =
                new ArrayList<>(
                        List.of("state=?", "payload=?", "updated_at=?", "version=version+1"));
        var values =
                new ArrayList<Object>(
                        List.of(
                                state,
                                mapper.writeValueAsString(entity),
                                Timestamp.from(Instant.now())));
        extraColumns.forEach(
                (column, value) -> {
                    assignments.add(column + "=?");
                    values.add(value);
                });
        values.add(entity.getId().toString());
        values.add(entity.getVersion());
        String sql =
                "UPDATE "
                        + table
                        + " SET "
                        + String.join(",", assignments)
                        + " WHERE id=? AND version=?";
        if (jdbc.update(sql, values.toArray()) != 1) {
            throw KnowledgeException.conflict("Resource version changed");
        }
        entity.setVersion(entity.getVersion() + 1);
    }
}
