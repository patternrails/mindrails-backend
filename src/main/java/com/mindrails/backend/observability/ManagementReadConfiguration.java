package com.mindrails.backend.observability;

import com.mindrails.backend.metrics.MetricsProperties;
import com.mindrails.backend.persistence.DatabaseProperties;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    MetricsProperties.class,
    DatabaseProperties.class
})
public class ManagementReadConfiguration {
    /**
     * Pool is wrapped rather than published as a business DataSource candidate. Never warms up on
     * startup.
     */
    @Bean(destroyMethod = "close")
    ReadStorage observabilityReadStorage(DatabaseProperties db) {
        var source = new HikariDataSource();
        source.setJdbcUrl(
                db.getUrl() == null ? "jdbc:mysql://127.0.0.1:3306/mindrails" : db.getUrl());
        source.setUsername(db.getUsername() == null ? "mindrails_app" : db.getUsername());
        source.setPassword(db.getPassword());
        source.setPoolName("mindrails-management-reads");
        source.setMaximumPoolSize(2);
        source.setMinimumIdle(0);
        source.setInitializationFailTimeout(-1);
        source.setConnectionTimeout(1000);
        source.addDataSourceProperty("connectTimeout", "1000");
        source.addDataSourceProperty("socketTimeout", "3000");
        source.addDataSourceProperty("connectionTimeZone", "UTC");
        source.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        return new ReadStorage(source);
    }

    @Bean
    MetricsQueryStore metricsQueryStore(ReadStorage storage) {
        return new MysqlMetricsQueryStore(storage.jdbc());
    }

    public static final class ReadStorage implements AutoCloseable {
        private final HikariDataSource source;

        ReadStorage(HikariDataSource source) {
            this.source = source;
        }

        public JdbcTemplate jdbc() {
            var jdbc = new JdbcTemplate(source);
            jdbc.setQueryTimeout(3);
            return jdbc;
        }

        @Override
        public void close() {
            source.close();
        }
    }
}
