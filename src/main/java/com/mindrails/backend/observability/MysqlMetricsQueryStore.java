package com.mindrails.backend.observability;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.*;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

public final class MysqlMetricsQueryStore implements MetricsQueryStore {
    private static final String TOTALS = """
        COUNT(*) runs, COALESCE(SUM(status='SUCCEEDED'),0) succeeded_runs,
        COALESCE(SUM(status='FAILED'),0) failed_runs, COALESCE(SUM(status='CANCELLED'),0) cancelled_runs,
        COALESCE(SUM(status='RUNNING'),0) running_runs, COALESCE(SUM(status='INTERRUPTED'),0) interrupted_runs,
        COALESCE(SUM(model_call_count),0) model_calls, COALESCE(SUM(function_call_count),0) function_calls,
        COALESCE(SUM(mcp_tool_call_count),0) mcp_tool_calls, COALESCE(SUM(skill_load_count),0) skill_loads,
        COALESCE(SUM(known_input_tokens),0) input_tokens, COALESCE(SUM(known_output_tokens),0) output_tokens,
        COALESCE(SUM(known_total_tokens),0) total_tokens, COALESCE(SUM(input_known_call_count),0) input_known_calls,
        COALESCE(SUM(output_known_call_count),0) output_known_calls, COALESCE(SUM(usage_known_call_count),0) usage_known_calls
        """;
    private final NamedParameterJdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    public MysqlMetricsQueryStore(JdbcTemplate template) {
        jdbc = new NamedParameterJdbcTemplate(template);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(java.util.Objects.requireNonNull(template.getDataSource())));
        transaction.setReadOnly(true); transaction.setTimeout(3);
        transaction.setIsolationLevel(org.springframework.transaction.TransactionDefinition.ISOLATION_REPEATABLE_READ);
    }
    @Override public Data overview(ArchiveFilter filter) {
        return transaction.execute(ignored -> {
            var p = new MapSqlParameterSource(); String where = filter.sql(p);
            var summary = jdbc.queryForMap("SELECT " + TOTALS + ", MAX(persisted_at) persisted FROM metrics_runs" + where, p);
            var trend = jdbc.queryForList("SELECT DATE(started_at) day," + TOTALS + " FROM metrics_runs" + where + " GROUP BY DATE(started_at) ORDER BY day", p)
                    .stream().map(row -> new Point(((java.sql.Date)row.get("day")).toLocalDate(), totals(row))).toList();
            var models = jdbc.queryForList("SELECT COALESCE(model_name,'unknown') model_name," + TOTALS + " FROM metrics_runs" + where
                    + " GROUP BY COALESCE(model_name,'unknown') ORDER BY model_calls DESC, model_name LIMIT 10", p)
                    .stream().map(row -> new Model((String)row.get("model_name"), totals(row))).toList();
            Instant persisted = summary.get("persisted") == null ? null : ((java.sql.Timestamp)summary.get("persisted")).toInstant();
            return new Data(totals(summary), trend, models, persisted);
        });
    }
    @Override public SessionData session(java.util.UUID sessionId) {
        var parameters = new MapSqlParameterSource();
        var where = new ArchiveFilter(null, null, null, sessionId, null).sql(parameters);
        var row = jdbc.queryForMap("SELECT " + TOTALS + ", MAX(persisted_at) persisted FROM metrics_runs" + where, parameters);
        return new SessionData(totals(row), row.get("persisted") == null ? null : ((java.sql.Timestamp) row.get("persisted")).toInstant());
    }
    private static long number(Map<String,Object> row, String key) { return ((Number)row.get(key)).longValue(); }
    private static Totals totals(Map<String,Object> row) {
        long calls = number(row,"model_calls"), in = number(row,"input_known_calls"), out = number(row,"output_known_calls"), all = number(row,"usage_known_calls");
        return new Totals(number(row,"runs"),number(row,"succeeded_runs"),number(row,"failed_runs"),number(row,"cancelled_runs"),number(row,"running_runs"),number(row,"interrupted_runs"),
                calls,number(row,"function_calls"),number(row,"mcp_tool_calls"),MetricsAggregates.known(calls,in,number(row,"input_tokens")),MetricsAggregates.known(calls,out,number(row,"output_tokens")),
                MetricsAggregates.known(calls,all,number(row,"total_tokens")),in,out,all,number(row,"skill_loads"));
    }
}
