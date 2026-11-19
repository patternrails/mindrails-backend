package com.mindrails.backend.provider;

import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.util.*;

public final class MysqlProviderRegistry extends ManagedProviderRegistry {
    private final JdbcTemplate jdbc;
    private final ProviderSecrets secrets;

    public MysqlProviderRegistry(
            JdbcTemplate jdbc, Environment env, ProvidersConfiguration loader) {
        super(load(jdbc, new ProviderSecrets(env)), loader);
        this.jdbc = jdbc;
        this.secrets = new ProviderSecrets(env);
    }

    private static Catalog load(JdbcTemplate jdbc, ProviderSecrets secrets) {
        var rows =
                jdbc.query(
                        "SELECT * FROM providers ORDER BY is_default DESC,sort_order,id",
                        (r, n) ->
                                new ProviderDefinition(
                                        UUID.fromString(r.getString("id")),
                                        r.getString("name"),
                                        r.getString("integration_mode"),
                                        r.getBoolean("streaming"),
                                        r.getString("base_url"),
                                        secrets.decrypt(r.getString("api_key")),
                                        r.getString("model_name"),
                                        (Boolean) r.getObject("reasoning"),
                                        r.getString("thinking_level"),
                                        r.getBigDecimal("model_cost"),
                                        (Integer) r.getObject("context_window"),
                                        (Integer) r.getObject("max_tokens")));
        return new Catalog(rows.isEmpty() ? null : rows.getFirst().name(), rows);
    }

    @Override
    protected void persist(Catalog next) {
        new TransactionTemplate(
                        new DataSourceTransactionManager(
                                Objects.requireNonNull(jdbc.getDataSource())))
                .executeWithoutResult(
                        status -> {
                            jdbc.queryForList("SELECT id FROM providers FOR UPDATE", String.class);
                            var previous = currentCatalog();
                            for (var old : previous.providers())
                                if (next.providers().stream()
                                        .noneMatch(d -> d.id().equals(old.id())))
                                    jdbc.update(
                                            "DELETE FROM providers WHERE id=?",
                                            old.id().toString());
                            if (!Objects.equals(previous.defaultProvider(), next.defaultProvider()))
                                jdbc.update(
                                        "UPDATE providers SET is_default=FALSE WHERE"
                                                + " is_default=TRUE");
                            int order = 0;
                            for (var d : next.providers()) {
                                int sortOrder = order++;
                                boolean isDefault = d.name().equals(next.defaultProvider());
                                var old =
                                        previous.providers().stream()
                                                .filter(p -> p.id().equals(d.id()))
                                                .findFirst();
                                if (old.isPresent()
                                        && old.get().equals(d)
                                        && Objects.equals(
                                                previous.defaultProvider(), next.defaultProvider()))
                                    continue;
                                jdbc.update(
                                        "INSERT INTO providers"
                                            + " (id,name,integration_mode,streaming,base_url,api_key,model_name,reasoning,thinking_level,model_cost,context_window,max_tokens,is_default,sort_order)"
                                            + " VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?) ON DUPLICATE"
                                            + " KEY UPDATE"
                                            + " name=VALUES(name),integration_mode=VALUES(integration_mode),streaming=VALUES(streaming),base_url=VALUES(base_url),api_key=VALUES(api_key),model_name=VALUES(model_name),reasoning=VALUES(reasoning),thinking_level=VALUES(thinking_level),model_cost=VALUES(model_cost),context_window=VALUES(context_window),max_tokens=VALUES(max_tokens),is_default=VALUES(is_default),sort_order=VALUES(sort_order)",
                                        d.id().toString(),
                                        d.name(),
                                        d.chatModel(),
                                        d.streaming() == null || d.streaming(),
                                        d.baseUrl(),
                                        secrets.encrypt(d.apiKey()),
                                        d.modelName(),
                                        d.reasoning(),
                                        d.thinkingLevel(),
                                        d.modelCost(),
                                        d.contextWindow(),
                                        d.maxTokens(),
                                        isDefault,
                                        sortOrder);
                            }
                        });
    }
}
