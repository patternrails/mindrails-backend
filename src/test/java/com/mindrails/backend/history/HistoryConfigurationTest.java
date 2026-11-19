package com.mindrails.backend.history;

import static org.assertj.core.api.Assertions.assertThat;

import com.mindrails.backend.agent.registry.AgentRegistry;
import com.mindrails.backend.agent.registry.AgentRegistryConfiguration;
import com.mindrails.backend.history.config.HistoryConfiguration;
import com.mindrails.backend.history.ChatHistory;
import com.mindrails.backend.history.store.HistoryStore;
import com.mindrails.backend.history.store.MysqlHistoryStore;
import com.mindrails.backend.persistence.PersistenceConfiguration;
import com.zaxxer.hikari.HikariDataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class HistoryConfigurationTest {
    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withUserConfiguration(HistoryConfiguration.class, PersistenceConfiguration.class, ChatHistory.class)
                    .withBean(
                            AgentRegistry.class,
                            () -> new AgentRegistryConfiguration().agentRegistry());

    @Test
    void mysqlHistoryUsesTheSharedPool() {
        runner.run(
                context -> {
                    assertThat(context).hasNotFailed().hasSingleBean(HikariDataSource.class);
                    assertThat(context.getBean(HistoryStore.class))
                            .isInstanceOf(MysqlHistoryStore.class);
                });
    }

}
