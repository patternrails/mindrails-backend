package com.mindrails.backend.agent.registry;

import com.mindrails.backend.provider.ProviderRegistry;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class SessionRegistryConfiguration {
    @Bean
    public SessionRegistry sessionRegistry(
            ProviderRegistry providers,
            ObjectMapper mapper,
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlSessionRegistry(jdbc, mapper, providers);
    }

    /** Programmatic in-memory registry, independent of application configuration. */
    public SessionRegistry sessionRegistry() {
        return new InMemorySessionRegistry();
    }
}
