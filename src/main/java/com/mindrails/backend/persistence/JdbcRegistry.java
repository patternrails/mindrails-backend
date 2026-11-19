package com.mindrails.backend.persistence;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Shared UUID, pagination, row-lock and version semantics for writable registries. */
public abstract class JdbcRegistry<T extends com.mindrails.backend.identity.UserOwnedResource> {
    protected final NamedParameterJdbcTemplate jdbc;
    protected final TransactionTemplate transactions;
    private final String table;
    private final String columns;
    private final Function<T, UUID> identity;
    private final ToLongFunction<T> version;
    private final BiConsumer<T, Long> setVersion;

    protected JdbcRegistry(JdbcTemplate jdbc, String table, String columns,
            Function<T, UUID> identity, ToLongFunction<T> version, BiConsumer<T, Long> setVersion) {
        this.jdbc = new NamedParameterJdbcTemplate(jdbc);
        this.transactions = new TransactionTemplate(
                new DataSourceTransactionManager(java.util.Objects.requireNonNull(jdbc.getDataSource())));
        this.table = table;
        this.columns = columns;
        this.identity = identity;
        this.version = version;
        this.setVersion = setVersion;
    }

    protected abstract T read(ResultSet rs) throws SQLException;
    protected abstract MapSqlParameterSource parameters(T entity);
    protected void validateSnapshot(T stored, T update) {}
    protected T enrich(T entity) { return entity; }
    protected String visibilityPredicate() { return "1=1"; }

    /** Child registries resolve parent ownership before creation; roots have no inherited owner. */
    protected UUID inheritedOwner(T entity) { return null; }

    private void normalizeOwner(T entity) {
        UUID inherited = inheritedOwner(entity);
        UUID owner = entity.getOwnerId() == null && inherited != null
                ? inherited : com.mindrails.backend.identity.ResourceAccess.owner(entity.getOwnerId());
        if (inherited != null) com.mindrails.backend.identity.ResourceAccess.requireBinding(inherited, owner);
        com.mindrails.backend.identity.ResourceAccess.requireOwner(owner);
        entity.setOwnerId(owner);
    }
    private String scope(MapSqlParameterSource values) {
        UUID user = com.mindrails.backend.identity.ResourceAccess.userId();
        if (user == null) return "";
        values.addValue("resource_user", user.toString());
        return " AND owner_id=:resource_user";
    }
    public boolean insertIfAbsent(T entity) {
        normalizeOwner(entity);
        var names = Arrays.stream(columns.split(",")).map(String::trim).toList();
        String values = names.stream().map(name -> ":" + name).collect(Collectors.joining(","));
        try {
            return jdbc.update("INSERT INTO " + table + " (" + columns + ") VALUES (" + values + ")", parameters(entity)) == 1;
        } catch (DuplicateKeyException duplicate) {
            // A primary-key collision may be retried. Other unique-key violations are real errors.
            if (findManaged(identity.apply(entity)).isPresent()) return false;
            throw duplicate;
        }
    }

    public Optional<T> findManaged(UUID id) { return find(id, false).map(this::enrich); }

    private Optional<T> find(UUID id, boolean lock) {
        if (id == null) return Optional.empty();
        var values = new MapSqlParameterSource("id", id.toString());
        var rows = jdbc.query("SELECT * FROM " + table + " WHERE id = :id AND " + visibilityPredicate() + scope(values) + (lock ? " FOR UPDATE" : ""),
                values, (rs, row) -> read(rs));
        return rows.stream().findFirst();
    }

    protected List<T> list(String predicate, MapSqlParameterSource parameters, int page, int size) {
        if (page < 0 || size < 1) throw new IllegalArgumentException("Invalid registry pagination");
        predicate = (predicate.isBlank() ? "WHERE " : predicate + " AND ") + visibilityPredicate() + scope(parameters);
        parameters.addValue("size", size).addValue("offset", (long) page * size);
        return jdbc.query("SELECT * FROM " + table + " " + predicate + " ORDER BY created_at, id LIMIT :size OFFSET :offset",
                parameters, (rs, row) -> read(rs)).stream().map(this::enrich).toList();
    }

    protected Optional<T> update(UUID id, Consumer<T> update) {
        return transactions.execute(status -> find(id, true).map(this::enrich).map(entity -> {
            long previousVersion = version.applyAsLong(entity);
            UUID owner = entity.getOwnerId();
            update.accept(entity);
            if (!java.util.Objects.equals(owner, entity.getOwnerId())) throw new IllegalArgumentException("Resource owner is immutable");
            if (!id.equals(identity.apply(entity))) throw new IllegalArgumentException("Registry identity is immutable");
            setVersion.accept(entity, previousVersion + 1);
            var values = parameters(entity).addValue("previous_version", previousVersion);
            String assignments = Arrays.stream(columns.split(",")).map(String::trim)
                    .filter(name -> !name.equals("id") && !name.equals("created_at"))
                    .map(name -> name + " = :" + name).collect(Collectors.joining(","));
            if (jdbc.update("UPDATE " + table + " SET " + assignments + " WHERE id = :id AND version = :previous_version", values) != 1) {
                throw new OptimisticLockingFailureException("Registry configuration changed concurrently");
            }
            return entity;
        }));
    }

    protected Optional<T> saveSnapshot(T entity) {
        UUID id = identity.apply(entity);
        long expectedVersion = version.applyAsLong(entity);
        return transactions.execute(status -> find(id, true).map(stored -> {
            if (!java.util.Objects.equals(stored.getOwnerId(), entity.getOwnerId())) throw new IllegalArgumentException("Resource owner is immutable");
            validateSnapshot(stored, entity);
            if (version.applyAsLong(stored) != expectedVersion) {
                throw new OptimisticLockingFailureException("Registry configuration changed concurrently");
            }
            var values = parameters(entity).addValue("version", expectedVersion + 1)
                    .addValue("previous_version", expectedVersion);
            String assignments = Arrays.stream(columns.split(",")).map(String::trim)
                    .filter(name -> !name.equals("id") && !name.equals("created_at"))
                    .map(name -> name + " = :" + name).collect(Collectors.joining(","));
            if (jdbc.update("UPDATE " + table + " SET " + assignments + " WHERE id = :id AND version = :previous_version", values) != 1) {
                throw new OptimisticLockingFailureException("Registry configuration changed concurrently");
            }
            setVersion.accept(entity, expectedVersion + 1);
            return entity;
        }));
    }

    public Optional<T> remove(UUID id) {
        return transactions.execute(status -> find(id, true).map(this::enrich).map(entity -> {
            jdbc.update("DELETE FROM " + table + " WHERE id = :id", new MapSqlParameterSource("id", id.toString()));
            return entity;
        }));
    }
}
