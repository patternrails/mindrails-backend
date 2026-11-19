package com.mindrails.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindrails.backend.embedding.QdrantProperties;
import com.mindrails.backend.mcp.service.internal.McpProperties;
import com.mindrails.backend.memory.agent.AgentMemoryProperties;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

class StartupConfigurationTest {
    @Test
    void applicationPropertiesSelectPersistentStores() {
        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=file:src/main/resources/application.properties")
                .withUserConfiguration(PersistenceConfiguration.class, StartupProperties.class)
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(HikariDataSource.class)
                            .hasSingleBean(JdbcTemplate.class);
                    assertThat(context.getBean(AgentMemoryProperties.class).getQdrant().getCollection()).isNotBlank();
                    assertThat(context.getBean(McpProperties.class).isStdioEnabled()).isTrue();
                    assertThat(context.getBean(QdrantProperties.class).getHost()).isNotBlank();
                    assertThat(context.getBean("memoryJdbcTemplate")).isSameAs(context.getBean("persistenceJdbcTemplate"));
                });
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties({AgentMemoryProperties.class, McpProperties.class,
            QdrantProperties.class})
    static class StartupProperties {}
}
