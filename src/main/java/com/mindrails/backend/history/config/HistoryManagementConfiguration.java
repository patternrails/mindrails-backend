package com.mindrails.backend.history.config;

import com.mindrails.backend.history.service.DefaultHistoryService;
import com.mindrails.backend.history.service.HistoryService;
import com.mindrails.backend.history.store.HistoryQueryStore;
import com.mindrails.backend.history.store.HistoryForkStore;
import com.mindrails.backend.history.store.MysqlHistoryForkStore;
import com.mindrails.backend.history.store.MysqlHistoryQueryStore;
import com.mindrails.backend.observability.ManagementReadConfiguration;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

@Configuration(proxyBeanMethods = false)
@Import(ManagementReadConfiguration.class)
public class HistoryManagementConfiguration {
    @Bean
    HistoryQueryStore historyQueryStore(ManagementReadConfiguration.ReadStorage storage) {
        return new MysqlHistoryQueryStore(storage.jdbc());
    }

    @Bean
    HistoryForkStore historyForkStore(
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlHistoryForkStore(jdbc);
    }

    @Bean
    HistoryService historyService(HistoryQueryStore store) {
        return new DefaultHistoryService(store);
    }
}
