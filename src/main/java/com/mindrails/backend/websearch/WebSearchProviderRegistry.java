package com.mindrails.backend.websearch;

import com.mindrails.backend.provider.ProviderSecrets;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

@Repository
public class WebSearchProviderRegistry {
    private static final String FIELDS =
            "id,name,type,endpoint,model_name,options,timeout_millis,active,version,created_at,updated_at";
    private static final TypeReference<Map<String, Object>> OPTIONS = new TypeReference<>() {};

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final ProviderSecrets secrets;
    private final ObjectMapper json;

    public WebSearchProviderRegistry(@Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc,
            @Qualifier("persistenceTransactionManager") PlatformTransactionManager transactionManager,
            Environment environment, ObjectMapper json) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactionManager);
        this.secrets = new ProviderSecrets(environment);
        this.json = json;
    }

    public List<WebSearchProvider> list(int page, int size) {
        return jdbc.query("SELECT " + FIELDS + " FROM web_search_providers ORDER BY active DESC,name,id LIMIT ? OFFSET ?",
                this::read, size, (long) page * size);
    }

    public Optional<WebSearchProvider> find(UUID id) {
        return jdbc.query("SELECT " + FIELDS + " FROM web_search_providers WHERE id=?", this::read, id.toString())
                .stream().findFirst();
    }

    Optional<Connection> activeConnection() {
        return jdbc.query("SELECT " + FIELDS + ",api_key FROM web_search_providers WHERE active_marker=1",
                this::readConnection)
                .stream().findFirst();
    }

    Optional<Connection> connection(UUID id) {
        return jdbc.query("SELECT " + FIELDS + ",api_key FROM web_search_providers WHERE id=?",
                this::readConnection, id.toString()).stream().findFirst();
    }

    private Connection readConnection(ResultSet row, int index) throws SQLException {
        return new Connection(read(row, index), secrets.decrypt(row.getString("api_key")));
    }

    /** The lock row also serializes the first two inserts into an empty catalog. */
    <T> T write(Supplier<T> mutation) {
        return transaction.execute(status -> {
            jdbc.queryForObject("SELECT id FROM web_search_provider_catalog WHERE id=1 FOR UPDATE", Integer.class);
            return mutation.get();
        });
    }

    void insert(UUID id, WebSearchProvider.Input input) {
        boolean first = jdbc.queryForObject("SELECT COUNT(*) FROM web_search_providers WHERE active_marker=1",
                Integer.class) == 0;
        jdbc.update("""
                INSERT INTO web_search_providers (id,name,type,endpoint,api_key,model_name,options,timeout_millis,active)
                VALUES (?,?,?,?,?,?,?,?,?)
                """, id.toString(), input.name(), input.type(), input.endpoint(), secrets.encrypt(input.apiKey()),
                input.modelName(), json.writeValueAsString(input.options()), input.timeoutMillis(), first);
    }

    boolean update(UUID id, WebSearchProvider.Input input) {
        return jdbc.update("""
                UPDATE web_search_providers
                SET name=?,type=?,endpoint=?,api_key=COALESCE(?,api_key),model_name=?,options=?,
                    timeout_millis=?,version=version+1
                WHERE id=? AND version=?
                """, input.name(), input.type(), input.endpoint(), secrets.encrypt(input.apiKey()),
                input.modelName(), json.writeValueAsString(input.options()), input.timeoutMillis(),
                id.toString(), input.version()) == 1;
    }

    void activate(UUID id) {
        jdbc.update("UPDATE web_search_providers SET active=FALSE WHERE active_marker=1 AND id<>?", id.toString());
        jdbc.update("UPDATE web_search_providers SET active=TRUE WHERE id=?", id.toString());
    }

    void delete(WebSearchProvider provider) {
        jdbc.update("DELETE FROM web_search_providers WHERE id=?", provider.id().toString());
        if (provider.active()) {
            jdbc.update("UPDATE web_search_providers SET active=TRUE ORDER BY created_at,id LIMIT 1");
        }
    }

    private WebSearchProvider read(ResultSet row, int index) throws SQLException {
        return new WebSearchProvider(UUID.fromString(row.getString("id")), row.getString("name"),
                row.getString("type"), row.getString("endpoint"), row.getString("model_name"),
                Map.copyOf(json.readValue(row.getString("options"), OPTIONS)), row.getInt("timeout_millis"),
                row.getBoolean("active"), row.getLong("version"), row.getTimestamp("created_at").toInstant(),
                row.getTimestamp("updated_at").toInstant());
    }

    record Connection(WebSearchProvider provider, String apiKey) {
        @Override public String toString() {
            return "WebSearchProviderRegistry.Connection[redacted]";
        }
    }
}
