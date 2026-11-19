package com.mindrails.backend.memory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import dev.langchain4j.data.message.UserMessage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

class ChatMemoryStoreFactoryTest {
    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withUserConfiguration(ChatMemoryStoreFactory.class);

    @Test
    void requiresTheQualifiedJdbcTemplate() {
        runner.run(context -> assertThat(context).hasFailed().getFailure()
                .hasMessageContaining("memoryJdbcTemplate"));
    }

    @Test
    void mysqlUsesTheQualifiedJdbcTemplate() {
        var jdbc = mock(JdbcTemplate.class);
        runner.withBean("memoryJdbcTemplate", JdbcTemplate.class, () -> jdbc)
                .withBean("otherJdbcTemplate", JdbcTemplate.class, () -> mock(JdbcTemplate.class))
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    var memoryId = UUID.randomUUID();
                    var memory = context.getBean(ChatMemoryStoreFactory.class).buildChatMemory(memoryId);
                    memory.add(UserMessage.from("Remember me"));
                    verify(jdbc).update(contains("INSERT INTO chat_memory"), eq(memoryId.toString()), contains("Remember me"));
                });
    }
}
