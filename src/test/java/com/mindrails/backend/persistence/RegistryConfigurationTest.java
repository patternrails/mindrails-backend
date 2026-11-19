package com.mindrails.backend.persistence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mockConstruction;
import static org.mockito.Mockito.when;

import com.mindrails.backend.agent.registry.*;
import com.mindrails.backend.persistence.PersistenceConfiguration;
import com.mindrails.backend.provider.*;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import javax.sql.DataSource;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class RegistryConfigurationTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(PersistenceConfiguration.class,
                    ProvidersConfiguration.class, AgentRegistryConfiguration.class, SessionRegistryConfiguration.class)
            .withBean(ObjectMapper.class, () -> JsonMapper.builder().build())
            .withPropertyValues("OPENAI_API_KEY=test-openai", "DEEPSEEK_API_KEY=test-deepseek");

    @Test
    void wiresMysqlEntitiesAndAnEmptyMysqlProviderCatalog() {
        try (var templates = mockConstruction(JdbcTemplate.class,
                (jdbc, construction) -> when(jdbc.getDataSource())
                        .thenReturn((DataSource) construction.arguments().getFirst()))) {
            runner.run(context -> {
                assertThat(context).hasNotFailed().hasSingleBean(HikariDataSource.class);
                assertThat(context.getBean(ProviderRegistry.class)).isInstanceOf(MysqlProviderRegistry.class);
                assertThat(context.getBean(AgentRegistry.class).getClass().getSimpleName()).isEqualTo("MysqlAgentRegistry");
                assertThat(context.getBean(SessionRegistry.class).getClass().getSimpleName()).isEqualTo("MysqlSessionRegistry");
                assertThat(context.getBean(ReadOnlyAgentRegistry.class)).isSameAs(context.getBean(AgentRegistry.class));
                assertThat(context.getBean(ReadOnlySessionRegistry.class)).isSameAs(context.getBean(SessionRegistry.class));
                assertThat(context.getBean(ProviderRegistry.class).defaultProvider()).isEmpty();
                assertThat(context.getBean(ProviderRegistry.class).list(0, 100)).isEmpty();
            });
        }
    }
}
