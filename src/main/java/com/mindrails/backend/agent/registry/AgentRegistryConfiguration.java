package com.mindrails.backend.agent.registry;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
public class AgentRegistryConfiguration {
    @Bean
    public AgentRegistry agentRegistry(
            SessionRegistry sessions,
            ObjectProvider<TaskRegistry> tasks,
            ObjectMapper mapper,
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlAgentRegistry(jdbc, mapper, sessions, tasks.getIfAvailable());
    }

    /** Programmatic in-memory registry, independent of application configuration. */
    public AgentRegistry agentRegistry() {
        return new InMemoryAgentRegistry();
    }
}
