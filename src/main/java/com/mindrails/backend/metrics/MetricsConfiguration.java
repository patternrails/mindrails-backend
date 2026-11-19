package com.mindrails.backend.metrics;

import com.mindrails.backend.persistence.DatabaseProperties;
import com.zaxxer.hikari.HikariDataSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import lombok.extern.slf4j.Slf4j;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({MetricsProperties.class, DatabaseProperties.class})
@Slf4j
public class MetricsConfiguration {
    @Bean MetricsHealth metricsHealth() { return new MetricsHealth(); }
    @Bean MetricsSnapshotBuffer metricsSnapshotBuffer(MetricsProperties properties, MetricsHealth health) {
        return new MetricsSnapshotBuffer(Math.max(1, properties.getMaxRuns()),
                Math.max(8192, properties.getMaxBufferBytes()), properties.getLateCallbackRetention(), health);
    }
    @Bean MetricsRecorder metricsRecorder(MetricsProperties properties, MetricsSnapshotBuffer buffer, MetricsHealth health) {
        return new MetricsRecorder(properties, buffer, health);
    }
    @Bean(initMethod = "start", destroyMethod = "close")
    MetricsWriter metricsWriter(MetricsRepository repository, MetricsSnapshotBuffer buffer,
            MetricsProperties properties, MetricsHealth health) {
        return new MetricsWriter(repository, buffer, properties, health);
    }
    @Bean(destroyMethod = "close") MetricsStorage metricsRepository(MetricsProperties properties, DatabaseProperties database, MetricsHealth health) {
        if (!properties.valid()) {
            log.warn("Invalid metrics configuration; collection disabled");
            return new MetricsStorage(ignored -> {}, null);
        }
        HikariDataSource source = new HikariDataSource();
        try {
            source.setJdbcUrl(database.getUrl() == null ? "jdbc:mysql://127.0.0.1:3306/mindrails" : database.getUrl());
            source.setUsername(database.getUsername() == null ? "mindrails_app" : database.getUsername());
            source.setPassword(database.getPassword());
            source.setPoolName("mindrails-metrics"); source.setMaximumPoolSize(properties.getPoolSize());
            source.setMinimumIdle(0); source.setInitializationFailTimeout(-1); source.setConnectionTimeout(1000);
            source.addDataSourceProperty("connectTimeout", "1000"); source.addDataSourceProperty("socketTimeout", "2000");
            source.addDataSourceProperty("connectionTimeZone", "UTC"); source.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
            var jdbc = new JdbcTemplate(source); jdbc.setQueryTimeout(2);
            return new MetricsStorage(new MysqlMetricsRepository(jdbc, properties, health), source);
        } catch (RuntimeException failure) {
            source.close(); log.warn("Metrics storage configuration unavailable; collection continues with bounded dropping/retries");
            return new MetricsStorage(ignored -> { throw new IllegalStateException("Metrics storage unavailable"); }, null);
        }
    }
    public static final class MetricsStorage implements MetricsRepository, AutoCloseable {
        private final MetricsRepository delegate;
        private final HikariDataSource source;
        MetricsStorage(MetricsRepository delegate, HikariDataSource source) { this.delegate = delegate; this.source = source; }
        @Override public void save(MetricsSnapshot snapshot) { delegate.save(snapshot); }
        @Override public void cleanup(java.time.Instant detailsBefore, java.time.Instant runsBefore) { delegate.cleanup(detailsBefore, runsBefore); }
        @Override public void recoverStoppedInstance(java.util.UUID instanceId) { delegate.recoverStoppedInstance(instanceId); }
        @Override public void close() { if (source != null) source.close(); }
    }
}
