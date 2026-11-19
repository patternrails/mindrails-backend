package com.mindrails.backend.memory;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindrails.backend.persistence.DatabaseProperties;
import com.mindrails.backend.persistence.PersistenceConfiguration;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.StandardEnvironment;

class ChatMemoryPropertiesTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PersistenceConfiguration.class)
            .withInitializer(context -> context.getEnvironment().getPropertySources()
                    .remove(StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME));

    @Test
    void defaultMysqlUsesOnlySharedConnectionSettings() {
        runner.withPropertyValues("mindrails.mysql.url=jdbc:mysql://127.0.0.1:1/shared",
                        "mindrails.mysql.username=shared-user", "mindrails.mysql.password=shared-password")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(HikariDataSource.class);
                    var properties = context.getBean(ChatMemoryProperties.class);
                    assertThat(properties.getMaxMessages()).isEqualTo(100);
                    var source = context.getBean(HikariDataSource.class);
                    assertThat(source.getJdbcUrl()).isEqualTo("jdbc:mysql://127.0.0.1:1/shared");
                    assertThat(source.getUsername()).isEqualTo("shared-user");
                    assertThat(source.getPassword()).isEqualTo("shared-password");
                    assertThat(context.getBean("memoryJdbcTemplate")).isSameAs(context.getBean("persistenceJdbcTemplate"));
                });
    }

    @Test
    void mainPropertiesDefineSharedMysqlSettings() {
        runner.withInitializer(new ConfigDataApplicationContextInitializer())
                .withPropertyValues("spring.config.location=file:src/main/resources/application.properties")
                .run(context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(HikariDataSource.class);
                    var database = context.getBean(DatabaseProperties.class);
                    assertThat(database.getUrl()).isEqualTo("jdbc:mysql://127.0.0.1:3306/mindrails");
                    assertThat(database.getUsername()).isEqualTo("mindrails_app");
                    assertThat(database.getPassword()).isEmpty();
                });
    }

    @ParameterizedTest
    @ValueSource(strings = {"mindrails.chat-memory.max-messages=0",
            "mindrails.chat-memory.max-messages=-1"})
    void rejectsInvalidConfiguration(String invalid) {
        runner.withPropertyValues(invalid)
                .run(context -> assertThat(context).hasFailed());
    }
}
