package com.mindrails.backend.mcp.registry;

import com.mindrails.backend.mcp.service.internal.McpProperties;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(McpProperties.class)
public class McpRegistryConfiguration {
    @Bean
    public McpRegistry mcpRegistry(
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlMcpRegistry(jdbc);
    }
}
