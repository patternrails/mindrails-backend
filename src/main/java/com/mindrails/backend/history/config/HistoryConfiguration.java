package com.mindrails.backend.history.config;

import com.mindrails.backend.history.store.HistoryStore;
import com.mindrails.backend.history.store.MysqlHistoryStore;
import com.mindrails.backend.persistence.PersistenceConfiguration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;


@Configuration(proxyBeanMethods = false)
@Import({PersistenceConfiguration.class, HistoryManagementConfiguration.class})
public class HistoryConfiguration {
    @Bean
    @Primary
    HistoryStore historyStore(
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlHistoryStore(jdbc);
    }
}
