package com.mindrails.backend.persistence;

import com.mindrails.backend.memory.ChatMemoryProperties;
import com.zaxxer.hikari.HikariDataSource;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;

import java.util.Objects;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({
    DatabaseProperties.class,
    ChatMemoryProperties.class
})
public class PersistenceConfiguration {
    @Bean(
            name = {"persistenceDataSource", "memoryDataSource"},
            destroyMethod = "close")
    HikariDataSource persistenceDataSource(DatabaseProperties properties) {
        var source = new HikariDataSource();
        source.setJdbcUrl(
                Objects.requireNonNullElse(
                        properties.getUrl(), "jdbc:mysql://127.0.0.1:3306/mindrails"));
        source.setUsername(
                Objects.requireNonNullElse(properties.getUsername(), "mindrails_app"));
        source.setPassword(properties.getPassword());
        source.setPoolName("mindrails-persistence");
        source.setConnectionTimeout(5000);
        source.addDataSourceProperty("connectionTimeZone", "UTC");
        source.addDataSourceProperty("forceConnectionTimeZoneToSession", "true");
        return source;
    }

    @Bean(name = {"persistenceJdbcTemplate", "memoryJdbcTemplate"})
    JdbcTemplate persistenceJdbcTemplate(HikariDataSource persistenceDataSource) {
        return new JdbcTemplate(persistenceDataSource);
    }

    @Bean
    DataSourceTransactionManager persistenceTransactionManager(
            HikariDataSource persistenceDataSource) {
        return new DataSourceTransactionManager(persistenceDataSource);
    }
}
