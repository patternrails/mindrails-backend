package com.mindrails.backend.mcp.registry;

import com.mindrails.backend.common.ServiceResult;
import com.mindrails.backend.common.ServiceResult.ErrorCode;
import com.mindrails.backend.mcp.model.McpConnectionConfig;
import com.mindrails.backend.mcp.model.McpServer;
import com.mindrails.backend.mcp.model.McpToolCatalog;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import com.mindrails.backend.identity.ResourceAccess;

public final class MysqlMcpRegistry implements McpRegistry {
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transactions;
    private final JsonMapper json = JsonMapper.builder().build();

    public MysqlMcpRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
        transactions =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getDataSource())));
        transactions.setTimeout(5);
    }

    @Override
    public List<McpServer> list() {
        UUID user = ResourceAccess.userId();
        return user == null ? jdbc.query("SELECT * FROM mcp_servers ORDER BY name,id", this::readServer)
                : jdbc.query("SELECT * FROM mcp_servers WHERE owner_id=? ORDER BY name,id", this::readServer, user.toString());
    }

    @Override
    public Optional<McpServer> find(UUID id) {
        var user=ResourceAccess.userId();
        return jdbc
                .query("SELECT * FROM mcp_servers WHERE id=?" + (user==null?"":" AND owner_id=?"), this::readServer,
                        user==null?new Object[]{id.toString()}:new Object[]{id.toString(),user.toString()})
                .stream()
                .filter(server -> ResourceAccess.visible(server.ownerId())).findFirst();
    }

    @Override
    public ServiceResult<Void> saveAll(
            List<McpServer> batch, Map<UUID, Long> expectedVersions, int maxServers) {
        return transactions.execute(
                status -> {
                    lockRegistry();
                    var previous = new HashMap<UUID, McpServer>();
                    list().forEach(server -> previous.put(server.id(), server));
                    var names = new HashMap<String, UUID>();
                    previous.values().forEach(server -> names.put(server.name(), server.id()));
                    int count = previous.size();
                    // Validate the complete batch before writing, under the same database lock.
                    for (var server : batch) {
                        ResourceAccess.requireOwner(server.ownerId());
                        var old = previous.get(server.id());
                        // Check collisions against the complete ID namespace, not just this user's list.
                        var existingOwner = jdbc.queryForList("SELECT owner_id FROM mcp_servers WHERE id=?", String.class, server.id().toString());
                        if (!existingOwner.isEmpty()) ResourceAccess.requireBinding(server.ownerId(), UUID.fromString(existingOwner.getFirst()));
                        if (old != null) ResourceAccess.requireBinding(old.ownerId(), server.ownerId());
                        Long expected = expectedVersions.get(server.id());
                        if (expected != null && old == null) {
                            return ServiceResult.failure(
                                    ErrorCode.NOT_FOUND, "MCP server not found");
                        }
                        if (expected == null && old != null
                                || expected != null && old.version() != expected) {
                            return ServiceResult.failure(
                                    ErrorCode.CONFLICT,
                                    "MCP server was edited; refresh before retrying");
                        }
                        UUID duplicate = names.putIfAbsent(server.name(), server.id());
                        if (duplicate != null && !duplicate.equals(server.id())) {
                            return ServiceResult.failure(
                                    ErrorCode.CONFLICT, "Server already exists: " + server.name());
                        }
                        if (old == null) {
                            count++;
                        }
                    }
                    if (count > maxServers) {
                        return ServiceResult.failure(
                                ErrorCode.INVALID_ARGUMENT, "MCP server limit exceeded");
                    }
                    for (var server : batch) {
                        if (expectedVersions.containsKey(server.id())) {
                            jdbc.update(
                                    """
                                    UPDATE mcp_servers SET config=?,enabled=?,version=?,updated_at=? WHERE id=?
                                    """,
                                    json.writeValueAsString(server.config()),
                                    server.enabled(),
                                    server.version(),
                                    Timestamp.from(server.updatedAt()),
                                    server.id().toString());
                        } else {
                            jdbc.update(
                                    """
                                    INSERT INTO mcp_servers(id,owner_id,name,config,enabled,version,created_at,updated_at)
                                    VALUES(?,?,?,?,?,?,?,?)
                                    """,
                                    server.id().toString(),
                                    server.ownerId().toString(),
                                    server.name(),
                                    json.writeValueAsString(server.config()),
                                    server.enabled(),
                                    server.version(),
                                    Timestamp.from(server.createdAt()),
                                    Timestamp.from(server.updatedAt()));
                        }
                    }
                    return ServiceResult.success();
                });
    }

    @Override
    public ServiceResult<Void> remove(UUID id, long expectedVersion) {
        return transactions.execute(
                status -> {
                    lockRegistry();
                    var old = find(id).orElse(null);
                    if (old == null) {
                        return ServiceResult.failure(ErrorCode.NOT_FOUND, "MCP server not found");
                    }
                    if (old.version() != expectedVersion) {
                        return ServiceResult.failure(
                                ErrorCode.CONFLICT,
                                "MCP server was edited; refresh before retrying");
                    }
                    jdbc.update("DELETE FROM mcp_servers WHERE id=?", id.toString());
                    return ServiceResult.success();
                });
    }

    @Override
    public Optional<McpToolCatalog> findCatalog(UUID id) {
        if (find(id).isEmpty()) return Optional.empty();
        return jdbc
                .query(
                        "SELECT * FROM mcp_tool_catalogs WHERE server_id=?",
                        (row, index) ->
                                new McpToolCatalog(
                                        row.getLong("config_version"),
                                        row.getTimestamp("discovered_at").toInstant(),
                                        json.readValue(
                                                row.getString("tools"),
                                                new TypeReference<List<Map<String, Object>>>() {})),
                        id.toString())
                .stream()
                .findFirst();
    }

    @Override
    public void saveCatalog(UUID id, McpToolCatalog catalog) {
        transactions.executeWithoutResult(
                status -> {
                    lockRegistry();
                    if (find(id).filter(server -> server.version() == catalog.version())
                            .isEmpty()) {
                        return;
                    }
                    jdbc.update(
                            """
                            INSERT INTO mcp_tool_catalogs(server_id,config_version,discovered_at,tools) VALUES(?,?,?,?)
                            ON DUPLICATE KEY UPDATE config_version=VALUES(config_version),
                            discovered_at=VALUES(discovered_at),tools=VALUES(tools)
                            """,
                            id.toString(),
                            catalog.version(),
                            Timestamp.from(catalog.discoveredAt()),
                            json.writeValueAsString(catalog.tools()));
                });
    }

    private void lockRegistry() {
        jdbc.queryForObject(
                "SELECT id FROM mcp_registry_lock WHERE id=1 FOR UPDATE", Integer.class);
    }

    private McpServer readServer(ResultSet row, int index) throws SQLException {
        return new McpServer(
                UUID.fromString(row.getString("id")),
                UUID.fromString(row.getString("owner_id")),
                row.getString("name"),
                json.readValue(row.getString("config"), McpConnectionConfig.class),
                row.getBoolean("enabled"),
                row.getLong("version"),
                row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }
}
