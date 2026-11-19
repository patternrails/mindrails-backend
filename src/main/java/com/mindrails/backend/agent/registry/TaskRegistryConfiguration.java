package com.mindrails.backend.agent.registry;

import com.mindrails.backend.provider.ProviderRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import java.util.concurrent.*;
import tools.jackson.databind.ObjectMapper;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
public class TaskRegistryConfiguration {
    @Bean TaskRegistry taskRegistry(ProviderRegistry providers, ObjectMapper mapper,
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlTaskRegistry(jdbc, mapper, providers);
    }
    @Bean TaskExecutionStore taskExecutionStore(ObjectMapper mapper,
            @Qualifier("persistenceJdbcTemplate") JdbcTemplate jdbc) {
        return new MysqlTaskExecutionStore(jdbc, mapper);
    }
    @Bean(name = "taskExecutionExecutor", destroyMethod = "shutdown") ExecutorService taskExecutor() {
        return new ThreadPoolExecutor(4, 16, 60, TimeUnit.SECONDS, new ArrayBlockingQueue<>(100),
                Thread.ofPlatform().name("task-execution-", 0).factory(), new ThreadPoolExecutor.AbortPolicy());
    }
    @Bean ThreadPoolTaskScheduler taskCronScheduler() {
        var scheduler = new ThreadPoolTaskScheduler(); scheduler.setPoolSize(2); scheduler.setThreadNamePrefix("task-cron-");
        scheduler.setRemoveOnCancelPolicy(true); return scheduler;
    }
}
